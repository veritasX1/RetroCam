package com.retrocam.app.camera.gl

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGLExt
import android.view.Surface
import com.retrocam.app.data.RenderLook
import java.io.File
import java.io.FileDescriptor
import java.nio.ByteBuffer

/**
 * Bakes a [RenderLook] into an already-recorded video file, frame by
 * frame, via the exact same GL shader math the live preview/video used to
 * apply live (LookRenderer/ShaderSource) - decode -> GL look pass ->
 * re-encode -> mux, with the audio track copied through untouched. See
 * CameraViewModel.tryBind's long comment for why this exists: VideoCapture
 * no longer goes through a live CameraEffect at all (that was capping
 * resolution for the whole camera session, not just video), so the look
 * is applied here instead, once, against the real recorded resolution.
 *
 * This is the well-established Android "decode-edit-encode" MediaCodec
 * pattern (see Grafika's ContinuousCaptureActivity / Google's
 * ExtractDecodeEditEncodeMuxTest for the same shape of pipeline) - a
 * decoder writes into a Surface backed by a SurfaceTexture (exactly like a
 * camera preview), each decoded frame is drawn through LookRenderer into
 * an encoder's input Surface, and the encoder's compressed output is
 * muxed into a new file alongside the original (untouched) audio samples.
 */
object VideoLookBaker {
    private const val TIMEOUT_US = 10_000L
    private const val MIME_AVC = MediaFormat.MIMETYPE_VIDEO_AVC

    /** Synchronous, blocking - call from a background thread. [sourceFd]
     * is read directly (caller owns opening/closing it); [output] is a
     * plain file this function creates fresh. */
    fun bake(context: Context, sourceFd: FileDescriptor, look: RenderLook, output: File) {
        val videoExtractor = MediaExtractor().apply { setDataSource(sourceFd) }
        val videoTrack = selectTrack(videoExtractor, "video/")
        check(videoTrack >= 0) { "No video track in source" }
        val inputFormat = videoExtractor.getTrackFormat(videoTrack)
        videoExtractor.selectTrack(videoTrack)

        val width = inputFormat.getInteger(MediaFormat.KEY_WIDTH)
        val height = inputFormat.getInteger(MediaFormat.KEY_HEIGHT)
        val frameRate = runCatching { inputFormat.getInteger(MediaFormat.KEY_FRAME_RATE) }.getOrDefault(30)
        val sourceBitRate = runCatching { inputFormat.getInteger(MediaFormat.KEY_BIT_RATE) }.getOrNull()
        // A modest bump over the source's own bitrate (or a standard
        // rule-of-thumb estimate if the source format didn't carry one) -
        // this is already a second encoding generation (camera -> H.264
        // once, now H.264 again), so erring slightly high avoids adding
        // visible extra compression loss on top of the regrade itself.
        val bitRate = ((sourceBitRate ?: (width * height * frameRate * 0.12).toInt()) * 1.3).toInt()
        // Source rotation metadata is deliberately NOT read/reapplied here
        // - see the long comment at maybeStartMuxer below for why: this
        // pipeline's own decode->render->encode output already comes out
        // correctly oriented with no hint needed, confirmed on-device.

        // --- Encoder: MediaCodec's own surface-input mode, so GL can draw
        // straight into what it consumes - no extra CPU-side pixel copy. ---
        val outputFormat = MediaFormat.createVideoFormat(MIME_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val (encoder, encoderInputSurface) = createStartedEncoder(outputFormat)

        val eglCore = EglCore()
        eglCore.init()
        val encoderEglSurface = eglCore.createWindowSurface(encoderInputSurface)
        eglCore.makeCurrent(encoderEglSurface)
        val renderer = LookRenderer()
        renderer.init(context)
        val oesTextureId = renderer.createOesTexture()

        // --- Decoder: output straight to a SurfaceTexture wrapping that
        // same OES texture id, exactly like a live camera frame. ---
        val frameLock = Object()
        var frameAvailable = false
        val surfaceTexture = SurfaceTexture(oesTextureId).apply {
            setDefaultBufferSize(width, height)
            setOnFrameAvailableListener {
                synchronized(frameLock) { frameAvailable = true; frameLock.notifyAll() }
            }
        }
        val decoderSurface = Surface(surfaceTexture)
        val decoder = createStartedDecoder(inputFormat.getString(MediaFormat.KEY_MIME)!!, inputFormat, decoderSurface)

        // --- Audio passthrough source (separate extractor instance so its
        // read position doesn't interfere with the video extractor's). ---
        val audioExtractor = MediaExtractor().apply { setDataSource(sourceFd) }
        val audioTrack = selectTrack(audioExtractor, "audio/")
        val audioFormat = if (audioTrack >= 0) audioExtractor.getTrackFormat(audioTrack) else null
        if (audioTrack >= 0) audioExtractor.selectTrack(audioTrack)

        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerVideoTrack = -1
        var muxerAudioTrack = -1
        var muxerStarted = false
        fun maybeStartMuxer(videoFormat: MediaFormat) {
            if (muxerStarted) return
            muxerVideoTrack = muxer.addTrack(videoFormat)
            if (audioFormat != null) muxerAudioTrack = muxer.addTrack(audioFormat)
            // Deliberately NOT reapplying rotationDegrees here - verified
            // on-device (frame-by-frame, with vs without ffmpeg's rotate-
            // to-match-metadata step) that this pipeline's own OUTPUT
            // pixels already come out correctly oriented with NO hint at
            // all, even though the SOURCE file's own pixels need one. Most
            // likely explanation: a video decoder's SurfaceTexture
            // transform matrix can itself already fold in the container's
            // rotation as part of the buffer transform info the decoder
            // HAL reports (unlike a camera's SurfaceTexture, which only
            // ever reports buffer/crop transforms, never physical device
            // rotation - that's handled entirely separately by CameraX).
            // Reapplying the source's hint here double-rotated the result
            // 180 - confirmed by pulling a baked file and comparing a
            // frame extracted with/without ffmpeg's autorotate. If this
            // needs revisiting on a different device/decoder where the
            // transform matrix does NOT fold in rotation, the fix would be
            // detecting that case rather than blindly reapplying
            // rotationDegrees again.
            muxer.start()
            muxerStarted = true
        }

        val texMatrix = FloatArray(16)

        try {
            val decoderInfo = MediaCodec.BufferInfo()
            val encoderInfo = MediaCodec.BufferInfo()
            var inputEos = false
            var outputEos = false

            while (!outputEos) {
                if (!inputEos) {
                    val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inIndex)!!
                        val sampleSize = videoExtractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEos = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, sampleSize, videoExtractor.sampleTime, 0)
                            videoExtractor.advance()
                        }
                    }
                }

                var decoderDraining = true
                while (decoderDraining) {
                    when (val outIndex = decoder.dequeueOutputBuffer(decoderInfo, TIMEOUT_US)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> decoderDraining = false
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED, MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {}
                        else -> {
                            val doRender = decoderInfo.size > 0
                            decoder.releaseOutputBuffer(outIndex, doRender)
                            if (doRender) {
                                synchronized(frameLock) {
                                    var waited = 0
                                    while (!frameAvailable && waited < 20) { frameLock.wait(50); waited++ }
                                    frameAvailable = false
                                }
                                surfaceTexture.updateTexImage()
                                surfaceTexture.getTransformMatrix(texMatrix)
                                eglCore.makeCurrent(encoderEglSurface)
                                // The decoder's own SurfaceTexture transform
                                // matrix, used directly - no CameraX
                                // SurfaceOutput involved here to combine it
                                // with, unlike the old live pipeline.
                                renderer.drawFrame(oesTextureId, texMatrix, look, width, height, width, height)
                                EGLExt.eglPresentationTimeANDROID(eglCore.eglDisplay, encoderEglSurface, decoderInfo.presentationTimeUs * 1000)
                                eglCore.swapBuffers(encoderEglSurface)
                            }
                            if (decoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                encoder.signalEndOfInputStream()
                                decoderDraining = false
                            }
                        }
                    }
                }

                var encoderDraining = true
                while (encoderDraining) {
                    when (val outIndex = encoder.dequeueOutputBuffer(encoderInfo, TIMEOUT_US)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> encoderDraining = false
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> maybeStartMuxer(encoder.outputFormat)
                        MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {}
                        else -> {
                            if (encoderInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                encoderInfo.size = 0
                            }
                            if (encoderInfo.size > 0 && muxerStarted) {
                                val data = encoder.getOutputBuffer(outIndex)!!
                                data.position(encoderInfo.offset)
                                data.limit(encoderInfo.offset + encoderInfo.size)
                                muxer.writeSampleData(muxerVideoTrack, data, encoderInfo)
                            }
                            encoder.releaseOutputBuffer(outIndex, false)
                            if (encoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                outputEos = true
                                encoderDraining = false
                            }
                        }
                    }
                }
            }

            if (audioTrack >= 0 && muxerAudioTrack >= 0) {
                copyAudioSamples(audioExtractor, muxer, muxerAudioTrack)
            }
        } finally {
            runCatching { muxer.stop() }
            muxer.release()
            decoder.stop(); decoder.release()
            encoder.stop(); encoder.release()
            surfaceTexture.release()
            decoderSurface.release()
            renderer.release()
            eglCore.releaseSurface(encoderEglSurface)
            eglCore.release()
            videoExtractor.release()
            audioExtractor.release()
            encoderInputSurface.release()
        }
    }

    // The hardware video codec block on this device (and many others) only
    // supports a limited number of concurrent encode/decode sessions - the
    // camera's own recording encoder session can still be mid-teardown for
    // a brief moment right after VideoRecordEvent.Finalize fires, which
    // this transcode's own decoder/encoder start() calls can race,
    // observed on-device as MediaCodec.CodecException / NO_MEMORY /
    // "Hardware is overloaded" from the vendor's video HAL. This is a
    // well-known, transient class of MediaCodec resource contention (not
    // an app logic bug), so retrying with a short backoff is the standard,
    // correct mitigation rather than something to "fix" by removing.
    //
    // A CodecException out of start() leaves that MediaCodec instance
    // itself in a dead/Released state (confirmed on-device: retrying
    // start() again on the SAME instance throws a second, unrelated
    // IllegalStateException - "valid only at Configured state") - each
    // retry has to create and configure a brand new MediaCodec instance,
    // not just call start() again on the one that just failed.
    private fun createStartedDecoder(mime: String, format: MediaFormat, surface: Surface, maxAttempts: Int = 4, delayMs: Long = 300): MediaCodec {
        var lastError: Throwable? = null
        repeat(maxAttempts) {
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, surface, null, 0)
            try {
                codec.start()
                return codec
            } catch (e: MediaCodec.CodecException) {
                lastError = e
                runCatching { codec.release() }
                Thread.sleep(delayMs)
            }
        }
        // The hardware AVC decoder on this device (c2.qti.avc.decoder)
        // reliably refused to start here even across several retries and
        // several seconds - the vendor video HAL logged "Hardware is
        // overloaded" every time, which held true well past any plausible
        // "still tearing down the previous recording session" window, so
        // this looks like a genuine concurrent-session ceiling on this
        // chipset (likely: it won't run a hardware decode + hardware
        // encode session at once at this resolution) rather than a purely
        // transient race. Google's software AVC decoder
        // (c2.android.avc.decoder / OMX.google.h264.decoder) is present on
        // this device (confirmed via `dumpsys media.player`) and doesn't
        // compete for the same hardware block the encoder needs - slower,
        // but reliable, and this is a one-shot background bake, not a
        // real-time path.
        if (mime == MIME_AVC) {
            runCatching {
                val codec = MediaCodec.createByCodecName("c2.android.avc.decoder")
                codec.configure(format, surface, null, 0)
                codec.start()
                return codec
            }.onFailure { lastError = it }
        }
        throw lastError ?: IllegalStateException("decoder start() failed after $maxAttempts attempts")
    }

    private fun createStartedEncoder(format: MediaFormat, maxAttempts: Int = 6, delayMs: Long = 300): Pair<MediaCodec, Surface> {
        var lastError: Throwable? = null
        repeat(maxAttempts) {
            val codec = MediaCodec.createEncoderByType(MIME_AVC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = codec.createInputSurface()
            try {
                codec.start()
                return codec to surface
            } catch (e: MediaCodec.CodecException) {
                lastError = e
                runCatching { codec.release() }
                Thread.sleep(delayMs)
            }
        }
        throw lastError ?: IllegalStateException("encoder start() failed after $maxAttempts attempts")
    }

    private fun copyAudioSamples(extractor: MediaExtractor, muxer: MediaMuxer, muxerTrack: Int) {
        val info = MediaCodec.BufferInfo()
        val buffer = ByteBuffer.allocate(1 shl 20)
        while (true) {
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            info.offset = 0
            info.size = size
            info.presentationTimeUs = extractor.sampleTime
            info.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            buffer.position(0)
            muxer.writeSampleData(muxerTrack, buffer, info)
            extractor.advance()
        }
    }

    private fun selectTrack(extractor: MediaExtractor, mimePrefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(mimePrefix)) return i
        }
        return -1
    }
}
