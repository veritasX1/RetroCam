package com.retrocam.app.camera

import android.content.Context
import android.media.AudioManager
import android.media.MediaActionSound
import android.media.ToneGenerator
import com.retrocam.app.data.ShutterSound

/**
 * Plays the selected shutter sound.
 *
 * CLICK uses [MediaActionSound.SHUTTER_CLICK] - the platform's real
 * built-in shutter sound, no audio asset needed. BEEP and CHUNK are
 * synthesized at runtime with [ToneGenerator] as a functional placeholder
 * (a short high tone / a short low tone) rather than a real recorded
 * sample, since no audio asset files were available to embed - drop
 * `shutter_beep.ogg` / `shutter_chunk.ogg` into res/raw and swap this to a
 * SoundPool-backed implementation for the real thing.
 *
 * On a device/region where Android itself forces an audible shutter sound
 * (Japan/South Korea), the OS plays its own click regardless of what this
 * class does - see [com.retrocam.app.data.SHUTTER_SOUND_REGION_NOTE].
 */
class ShutterSoundPlayer(private val context: Context) {
    private val mediaActionSound by lazy { MediaActionSound() }
    private var toneGenerator: ToneGenerator? = null

    fun play(sound: ShutterSound) {
        when (sound) {
            ShutterSound.OFF -> {}
            ShutterSound.CLICK -> mediaActionSound.play(MediaActionSound.SHUTTER_CLICK)
            ShutterSound.BEEP -> tone(ToneGenerator.TONE_PROP_BEEP, durationMs = 120)
            ShutterSound.CHUNK -> tone(ToneGenerator.TONE_PROP_NACK, durationMs = 180)
        }
    }

    private fun tone(type: Int, durationMs: Int) {
        val tg = toneGenerator ?: ToneGenerator(AudioManager.STREAM_SYSTEM, 90).also { toneGenerator = it }
        tg.startTone(type, durationMs)
    }

    fun release() {
        mediaActionSound.release()
        toneGenerator?.release()
        toneGenerator = null
    }
}
