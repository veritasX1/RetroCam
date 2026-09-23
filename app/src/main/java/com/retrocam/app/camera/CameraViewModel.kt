package com.retrocam.app.camera

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.retrocam.app.data.CaptureAspectRatio
import com.retrocam.app.data.CaptureMode
import com.retrocam.app.data.DateStampSettings
import com.retrocam.app.data.FilmStockPreset
import com.retrocam.app.data.GrainBlendMode
import com.retrocam.app.data.GrainOverride
import com.retrocam.app.data.LocationStampMode
import com.retrocam.app.data.NO_FILTER_DESCRIPTION
import com.retrocam.app.data.NO_FILTER_ID
import com.retrocam.app.data.Recipe
import com.retrocam.app.data.RecipeSeeder
import com.retrocam.app.data.RenderLook
import com.retrocam.app.data.RetroCamDatabase
import com.retrocam.app.data.SettingsRepository
import com.retrocam.app.data.ShutterSound
import com.retrocam.app.data.Softness
import com.retrocam.app.data.formatCoordinates
import com.retrocam.app.data.toDescription
import com.retrocam.app.data.toRenderLook
import com.retrocam.app.data.withGrainOverride
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

class CameraViewModel(app: android.app.Application) : AndroidViewModel(app) {

    private val db = RetroCamDatabase.get(app)
    private val settings = SettingsRepository(app)
    val shutterSoundPlayer = ShutterSoundPlayer(app)

    private fun <T> Flow<T>.eager(initial: T): StateFlow<T> =
        stateIn(viewModelScope, SharingStarted.Eagerly, initial)

    val recipes: StateFlow<List<Recipe>> = db.recipeDao().observeAll().eager(emptyList())
    val filmStocks: StateFlow<List<FilmStockPreset>> = db.filmStockDao().observeAll().eager(emptyList())

    // All persisted (SettingsRepository/DataStore) - the app reopens exactly
    // as it was left, including "no filter", which is also what an
    // unset/fresh-install preference resolves to (NO_FILTER_ID).
    val mode: StateFlow<CaptureMode> = settings.captureMode.eager(CaptureMode.PHOTO)
    val selectedRecipeId: StateFlow<Long> = settings.selectedRecipeId.eager(NO_FILTER_ID)
    val selectedFilmStockId: StateFlow<Long> = settings.selectedFilmStockId.eager(NO_FILTER_ID)
    val shutterSound: StateFlow<ShutterSound> = settings.shutterSound.eager(ShutterSound.CLICK)
    val softness: StateFlow<Softness> = settings.softness.eager(Softness.OFF)
    val grainOverride: StateFlow<GrainOverride> = settings.grainOverride.eager(GrainOverride.STANDARD)
    val captureAspectRatio: StateFlow<CaptureAspectRatio> = settings.captureAspectRatio.eager(CaptureAspectRatio.RATIO_4_3)
    val grainBlendMode: StateFlow<GrainBlendMode> = settings.grainBlendMode.eager(GrainBlendMode.FILMKORN)
    val videoFps: StateFlow<Int> = settings.videoFps.eager(0)
    val locationEnabled: StateFlow<Boolean> = settings.locationEnabled.eager(false)
    val dateStampSettings: StateFlow<DateStampSettings> = settings.dateStampSettings.eager(DateStampSettings())
    val locationStampMode: StateFlow<LocationStampMode> = settings.locationStampMode.eager(LocationStampMode.OFF)
    private val persistedCameraKey: StateFlow<String?> = settings.selectedCameraKey.eager(null)

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    // A camera switch tears down and rebuilds the whole GL/effect pipeline,
    // and the native camera HAL's own teardown of the old SurfaceTexture
    // can still be settling well after bindCamera() itself returns - not
    // just until doBind() completes, but for a bit longer than that (a
    // symbolized tombstone traced this to a genuine SIGSEGV race in
    // libgui's ConsumerBase::abandon(), confirmed to still occur even on a
    // current CameraX version, triggered by ~any further UI interaction
    // landing during that window - not just another camera switch).
    // There's no clean signal for "the native teardown is now actually
    // done", so this locks the UI elements that empirically correlate
    // with hitting it for a flat cooldown after any rebind, as a
    // practical mitigation rather than a real fix for the underlying race.
    private val _uiLockedAfterRebind = MutableStateFlow(false)
    val uiLockedAfterRebind: StateFlow<Boolean> = _uiLockedAfterRebind.asStateFlow()
    private var uiLockJob: Job? = null
    private fun lockUiBriefly() {
        uiLockJob?.cancel()
        _uiLockedAfterRebind.value = true
        uiLockJob = viewModelScope.launch {
            delay(2000)
            _uiLockedAfterRebind.value = false
        }
    }

    private val _recordingElapsedMs = MutableStateFlow(0L)
    val recordingElapsedMs: StateFlow<Long> = _recordingElapsedMs.asStateFlow()

    // Torch (continuous LED), not ImageCapture's own per-shot FLASH_MODE -
    // one control that works the same way for both photo and video (a
    // single flash pulse can't light a video anyway), and is simpler/more
    // reliable than juggling the AE precapture sequence FLASH_MODE_ON/AUTO
    // needs. Deliberately not persisted - nobody wants the LED silently on
    // from last session - and reset to off on every fresh bind (see
    // doBind), since a newly bound Camera object never inherits torch
    // state from whatever was bound before it, and the front camera
    // usually has no flash unit at all.
    private val _hasFlash = MutableStateFlow(false)
    val hasFlash: StateFlow<Boolean> = _hasFlash.asStateFlow()
    private val _flashEnabled = MutableStateFlow(false)
    val flashEnabled: StateFlow<Boolean> = _flashEnabled.asStateFlow()

    fun toggleFlash() {
        if (!_hasFlash.value) return
        val next = !_flashEnabled.value
        boundCamera?.cameraControl?.enableTorch(next)
        _flashEnabled.value = next
    }

    private val _availableCameras = MutableStateFlow<List<CameraOption>>(emptyList())
    val availableCameras: StateFlow<List<CameraOption>> = _availableCameras.asStateFlow()
    private val _activeCameraKey = MutableStateFlow<String?>(null)
    val activeCameraKey: StateFlow<String?> = _activeCameraKey.asStateFlow()
    // Same reason as _activeCameraKey vs persistedCameraKey below: tryBind
    // needs to know the JUST-selected mode synchronously, not wait for a
    // DataStore round-trip through `mode` (settings.captureMode.eager(...))
    // to catch up - selectMode() sets this immediately, before the
    // rebind it triggers even starts.
    private val _activeMode = MutableStateFlow<CaptureMode?>(null)

    private val _availableFps = MutableStateFlow<List<Int>>(emptyList())
    val availableFps: StateFlow<List<Int>> = _availableFps.asStateFlow()

    private val _zoomRatio = MutableStateFlow(1f)
    val zoomRatio: StateFlow<Float> = _zoomRatio.asStateFlow()
    private val _minZoomRatio = MutableStateFlow(1f)
    val minZoomRatio: StateFlow<Float> = _minZoomRatio.asStateFlow()
    private val _maxZoomRatio = MutableStateFlow(1f)
    val maxZoomRatio: StateFlow<Float> = _maxZoomRatio.asStateFlow()

    private val _lastCaptureUri = MutableStateFlow<Uri?>(null)
    val lastCaptureUri: StateFlow<Uri?> = _lastCaptureUri.asStateFlow()
    private val _lastCaptureIsVideo = MutableStateFlow(false)
    val lastCaptureIsVideo: StateFlow<Boolean> = _lastCaptureIsVideo.asStateFlow()

    private val currentLook = AtomicReference(RenderLook.NEUTRAL)
    private var recording: Recording? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var boundCamera: Camera? = null

    private var boundContext: Context? = null
    private var boundLifecycleOwner: LifecycleOwner? = null
    private var boundPreviewView: PreviewView? = null

    init {
        viewModelScope.launch {
            RecipeSeeder.seedIfEmpty(app, db)
        }
        viewModelScope.launch {
            combine(recipes, selectedRecipeId) { list, id ->
                if (id == NO_FILTER_ID) null else list.firstOrNull { it.id == id }
            }
                .combine(mode) { recipe, mode -> recipe to mode }
                .combine(filmStocks) { pair, stocks -> Triple(pair.first, pair.second, stocks) }
                .combine(selectedFilmStockId) { triple, stockId -> triple to stockId }
                .combine(softness) { (triple, stockId), extraSoftness -> Triple(triple, stockId, extraSoftness) }
                .combine(grainOverride) { (triple, stockId, extraSoftness), grain -> Triple(triple, stockId, extraSoftness to grain) }
                .combine(grainBlendMode) { (triple, stockId, softnessAndGrain), blendMode ->
                    val (extraSoftness, grain) = softnessAndGrain
                    updateLook(triple.first, triple.second, triple.third, stockId, extraSoftness, grain, blendMode)
                }
                .collect {}
        }
    }

    private fun updateLook(
        recipe: Recipe?,
        mode: CaptureMode,
        stocks: List<FilmStockPreset>,
        stockId: Long,
        extraSoftness: Softness,
        grainOverride: GrainOverride,
        grainBlendMode: GrainBlendMode,
    ) {
        // "No filter" stays completely clean - the grain switch only kicks
        // in once an actual recipe/film stock is applying a look, it
        // doesn't paint grain onto an otherwise untouched image.
        val base: RenderLook?
        val isNoFilter: Boolean
        when (mode) {
            CaptureMode.PHOTO -> { base = recipe?.toRenderLook(); isNoFilter = recipe == null }
            CaptureMode.VIDEO -> {
                isNoFilter = stockId == NO_FILTER_ID
                base = if (isNoFilter) null else stocks.firstOrNull { it.id == stockId }?.toRenderLook()
            }
        }
        val resolved = base ?: RenderLook.NEUTRAL
        val combinedSoftness = (resolved.softness + extraSoftness.blurAmount).coerceIn(0f, 1f)
        val withSoftness = resolved.copy(softness = combinedSoftness, grainBlendMode = grainBlendMode)
        currentLook.set(if (isNoFilter) withSoftness else withSoftness.withGrainOverride(grainOverride))
    }

    fun setGrainBlendMode(value: GrainBlendMode) { viewModelScope.launch { settings.setGrainBlendMode(value) } }

    // Now requires a rebind (see tryBind's ViewPort/UseCaseGroup comment -
    // only one of ImageCapture/VideoCapture is ever bound at a time), so
    // this needs the same guards selectCamera already uses: can't tear
    // down the pipeline a live Recording's encoder surface depends on,
    // and can't stack a second rebind on one still settling.
    fun selectMode(m: CaptureMode) {
        if (recording != null || uiLockedAfterRebind.value || m == mode.value) return
        _activeMode.value = m
        viewModelScope.launch { settings.setCaptureMode(m) }
        rebindIfPossible()
    }

    // Recipe/film-stock switching never touches the camera binding or its
    // GL surfaces - it only changes which look is read on the next frame -
    // but reproducing the crash above showed switching one shortly after a
    // camera switch reliably triggers it anyway (some interaction/CPU-load
    // effect landing during the fragile teardown window, not a direct code
    // path connection), so this is guarded by the same cooldown too.
    fun selectRecipe(id: Long) {
        if (uiLockedAfterRebind.value) return
        viewModelScope.launch { settings.setSelectedRecipeId(id) }
    }
    fun selectFilmStock(id: Long) {
        if (uiLockedAfterRebind.value) return
        viewModelScope.launch { settings.setSelectedFilmStockId(id) }
    }
    fun setGrainOverride(value: GrainOverride) { viewModelScope.launch { settings.setGrainOverride(value) } }
    fun setCaptureAspectRatio(value: CaptureAspectRatio) { viewModelScope.launch { settings.setCaptureAspectRatio(value) } }

    fun selectCamera(option: CameraOption) {
        // Switching the physical camera means tearing down and rebuilding
        // the whole GL/effect pipeline the active Recording's encoder
        // surface depends on - CameraX's Recorder doesn't support that
        // happening out from under a live recording (this used to crash
        // the GL thread reliably when tried), so it's disallowed here
        // rather than raced. Also blocked during the post-rebind cooldown
        // (see uiLockedAfterRebind) - stacking a second switch on top of
        // one that's still settling is exactly the pattern that reliably
        // reproduces the SurfaceTexture race.
        if (recording != null || uiLockedAfterRebind.value) return
        viewModelScope.launch { settings.setSelectedCameraKey(option.persistenceKey) }
        _activeCameraKey.value = option.persistenceKey
        rebindIfPossible()
    }

    fun setFps(fps: Int) {
        if (recording != null) return
        viewModelScope.launch { settings.setVideoFps(fps) }
        rebindIfPossible()
    }

    // CameraX bakes each use case's rotation handling in at bind time
    // (verified against CameraX 1.4.0's SurfaceOutputImpl bytecode back
    // when this app still routed everything through a custom
    // SurfaceProcessor effect, where this was especially strict - each
    // SurfaceOutput's rotation-compensation matrix was computed once at
    // onOutputSurface() and never recalculated) - calling
    // `.targetRotation = ...` on an already-bound Preview/ImageCapture/
    // VideoCapture doesn't retroactively affect it either way, effect or
    // not, so a rebind (same guarded path as selectCamera/setFps) is
    // still the only way to pick up a new rotation, and the only way to
    // get correctly *swapped* output dimensions (a landscape photo needs
    // a landscape-shaped capture, not a portrait one with the pixels
    // rotated inside it).
    private var lastKnownRotation: Int = Surface.ROTATION_0

    fun updateTargetRotation(rotation: Int) {
        if (rotation == lastKnownRotation) return
        lastKnownRotation = rotation
        // Rotating mid-recording won't retroactively fix the video (same
        // restriction as selectCamera/setFps, for the same reason - can't
        // swap the encoder surface out from under a live Recording) - the
        // new rotation is still remembered above and simply takes effect
        // on whatever rebind happens next.
        if (recording == null) rebindIfPossible()
    }

    fun setZoomRatio(ratio: Float) {
        boundCamera?.cameraControl?.setZoomRatio(ratio.coerceIn(_minZoomRatio.value, _maxZoomRatio.value))
    }

    private fun rebindIfPossible() {
        val context = boundContext
        val lifecycleOwner = boundLifecycleOwner
        val previewView = boundPreviewView
        if (context != null && lifecycleOwner != null && previewView != null) {
            lockUiBriefly()
            bindCamera(context, lifecycleOwner, previewView)
        }
    }

    // Bumped on every bindCamera() call; a pending rebind checks its own
    // snapshot against this before actually binding, so a second rebind
    // request arriving while the first is still waiting on the old
    // processor to release (e.g. two quick camera switches) doesn't leave
    // two overlapping binds in flight.
    private var rebindGeneration = 0

    fun bindCamera(context: Context, lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        boundContext = context
        boundLifecycleOwner = lifecycleOwner
        boundPreviewView = previewView
        val generation = ++rebindGeneration

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val provider = cameraProviderFuture.get()
            cameraProvider = provider

            val options = buildCameraOptions(provider.availableCameraInfos)
            _availableCameras.value = options
            if (options.isEmpty()) return@addListener

            val wantedKey = _activeCameraKey.value ?: persistedCameraKey.value
            val target = options.firstOrNull { it.persistenceKey == wantedKey }
                ?: options.firstOrNull { it.lensFacing == CameraSelector.LENS_FACING_BACK && it.zoomRatio in 0.95f..1.05f }
                ?: options.first()
            _activeCameraKey.value = target.persistenceKey
            _availableFps.value = availableFpsFor(target.cameraInfo)

            // unbindAll() first, standalone, still worth keeping even
            // without a custom SurfaceProcessor to wait on anymore (see
            // doBind's doc) - a plain, synchronous CameraX rebind (no
            // effect attached to anything, as of this session - see
            // tryBind) doesn't have the old effect's async GL-thread
            // teardown race this used to specifically guard against, but
            // unbinding before rebinding is still the standard, safest
            // CameraX pattern regardless.
            provider.unbindAll()
            if (generation == rebindGeneration) {
                doBind(provider, lifecycleOwner, previewView, context, target)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun doBind(
        provider: ProcessCameraProvider,
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        context: Context,
        target: CameraOption,
    ) {
        // Some cameras (front-facing ones especially) don't support every
        // combination we ask for - a fixed FPS that's fine on the back
        // camera can make bindToLifecycle throw on a different one. Retry
        // once with FPS forced back to auto instead of letting a bind
        // failure crash the app; if even that fails, bail out and leave
        // whatever was bound before in place.
        val requestedFps = videoFps.value
        val bound = tryBind(provider, lifecycleOwner, previewView, context, target, requestedFps)
            ?: if (requestedFps != 0) tryBind(provider, lifecycleOwner, previewView, context, target, 0) else null

        if (bound == null) {
            android.util.Log.e("RetroCam", "Could not bind camera ${target.persistenceKey}, leaving previous camera active")
            return
        }
        val (camera, previewUseCase, capture, video) = bound
        preview = previewUseCase
        imageCapture = capture
        videoCapture = video
        _hasFlash.value = camera.cameraInfo.hasFlashUnit()
        _flashEnabled.value = false
    }

    private data class BindResult(
        val camera: Camera,
        val preview: Preview,
        val imageCapture: ImageCapture?,
        val videoCapture: VideoCapture<Recorder>?,
    )

    private fun tryBind(
        provider: ProcessCameraProvider,
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        context: Context,
        target: CameraOption,
        fps: Int,
    ): BindResult? {
        // No live GL effect is attached to anything bound here anymore -
        // see the long comment below on why. targetRotation still needs
        // to be correct at build time regardless (a rebind, not a live
        // property update, is still how a rotation change actually takes
        // effect - CameraX bakes each use case's rotation handling in at
        // bind time either way, effect or not).
        // Preview deliberately targets the SCREEN's own aspect ratio, not
        // whichever capture use case is active (4:3 photo / 16:9 video) -
        // a real, user-reported bug found this session: with no explicit
        // aspect ratio, CameraX's default resolution selection pulled
        // Preview toward matching ImageCapture's 4:3 (confirmed via
        // `dumpsys media.camera`'s active streams: Preview's own stream
        // negotiated as 1600x1200, exactly 4:3) - but PreviewView/
        // SurfaceView fills the FULL, much wider ~2.22:1 landscape screen,
        // so center-crop scaling to fill that mismatched shape cropped
        // away roughly 40% of the vertical FOV, which is exactly what
        // read as the live viewfinder being "zoomed in" compared to the
        // stock camera app from the same physical position. Preview
        // showing a wider FOV than the final 4:3 photo or 16:9 video
        // actually keeps is normal, expected camera-app behavior, not a
        // new inconsistency - deliberately NOT using a shared ViewPort
        // here for exactly that reason (a ViewPort would force Preview
        // back into the capture use case's own crop, reintroducing this).
        // AspectRatioStrategy only accepts the two AspectRatio buckets
        // (4:3 / 16:9), not an arbitrary Rational matching the screen's
        // own (much wider, ~20:9) proportions exactly - 16:9 is by far
        // the closer of the two, and this is a normal camera-app tradeoff
        // regardless (the viewfinder showing a bit more FOV than the
        // final 4:3 photo/16:9 video actually keeps).
        val preview = Preview.Builder()
            .apply { applyFixedFps(fps); setTargetRotation(lastKnownRotation) }
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .build()
            )
            .build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
        // Only the use case matching the CURRENT mode (photo vs video) is
        // ever built and bound - see the ViewPort/UseCaseGroup comment
        // below for why. Whichever one isn't active this bind is simply
        // null; takePhoto()/startRecording() already no-op safely if
        // called with nothing bound (can't happen in practice - the
        // shutter button itself already dispatches based on the same
        // mode, see CameraScreen's ShutterButton).
        val currentMode = _activeMode.value ?: mode.value
        // MINIMIZE_LATENCY (CameraX's default) trades quality for speed -
        // fine for a burst-shooting app, wrong for one whose whole point
        // is the photo looking as good as the sensor can produce before
        // the retro look is even applied.
        val capture = if (currentMode == CaptureMode.PHOTO) {
            ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setTargetRotation(lastKnownRotation)
                .build()
        } else null
        // HIGHEST, not FHD - raw capture should reach the best resolution
        // the encoder/camera combination actually supports; the retro
        // look gets baked in afterward at whatever that resolution turns
        // out to be (VideoPostProcessor/VideoLookBaker), not live at a
        // capped resolution like before.
        val video = if (currentMode == CaptureMode.VIDEO) {
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                .build()
            VideoCapture.Builder(recorder).apply { applyFixedFps(fps); setTargetRotation(lastKnownRotation) }.build()
        } else null

        // No CameraEffect/RetroCamSurfaceProcessor attached to Preview,
        // ImageCapture, or VideoCapture anymore - the live GL "look"
        // pipeline that used to intercept every frame live is gone from
        // capture entirely. Two reasons, found together this session:
        //  1. Measured, then root-caused: routing IMAGE_CAPTURE through
        //     that shared live effect capped still-photo resolution well
        //     below the sensor's real capability (3264x2448 vs the stock
        //     camera app's 4080x3072 on this device). Confirmed via
        //     CameraX's own SupportedOutputSizesCollector debug logging
        //     that merely having ANY CameraEffect present in the
        //     UseCaseGroup narrows the WHOLE session's candidate output-
        //     size list, for every use case in the group, regardless of
        //     which ones the effect actually targets - tried scoping the
        //     effect down to VIDEO_CAPTURE only and Preview/ImageCapture
        //     were still capped identically. Confirmed against the stock
        //     camera app's own APK (org.lineageos.aperture, also CameraX-
        //     based) that it never references CameraEffect/addEffect at
        //     all - it reaches full resolution simply by never attaching
        //     an effect to anything.
        //  2. The only way to actually get that resolution back was to
        //     drop the effect from the whole session, not just
        //     ImageCapture - which also means Preview no longer shows a
        //     live-graded viewfinder. Accepted deliberately (user's
        //     framing): a real viewfinder/rangefinder camera never showed
        //     an exact preview of the exposed film either, so this isn't
        //     a regression from that principle, just this app actually
        //     matching it. The look itself is baked in afterward instead,
        //     for BOTH photo (PhotoLookBaker, since this session) and
        //     video (VideoLookBaker, this same change) - once, offscreen,
        //     against the real full-resolution capture, via the exact
        //     same shader math the old live pipeline used.
        // Bonus: this also sidesteps the whole documented camera-switch
        // SIGSEGV crash category (see README) at its root, for normal
        // preview/photo/video use - that crash was specifically a race in
        // RetroCamSurfaceProcessor's own SurfaceTexture teardown on every
        // camera switch, and there's no longer a live SurfaceProcessor
        // whose teardown a camera switch needs to race at all. Worth
        // confirming with real stress-testing before declaring it fixed,
        // but the root mechanism is now simply gone from this path.
        //
        // Only ONE of ImageCapture/VideoCapture is ever bound at once (see
        // currentMode above) - matches how a stock camera app behaves
        // (switching photo/video re-binds, rather than keeping every use
        // case live simultaneously), and keeps the resolution/candidate-
        // size negotiation for the active capture type as unconstrained
        // as possible by not sharing a session with the other capture
        // type's own requirements. No shared ViewPort - see Preview's own
        // ResolutionSelector comment above for why: a ViewPort would force
        // Preview's crop to match the capture use case's own aspect
        // ratio, which is the exact bug that comment describes.
        val groupBuilder = UseCaseGroup.Builder()
            .addUseCase(preview)
        capture?.let { groupBuilder.addUseCase(it) }
        video?.let { groupBuilder.addUseCase(it) }
        val group = groupBuilder.build()

        return try {
            val camera = provider.bindToLifecycle(lifecycleOwner, target.toSelector(), group)
            boundCamera = camera

            val zoomState = camera.cameraInfo.zoomState.value
            _zoomRatio.value = zoomState?.zoomRatio ?: 1f
            _minZoomRatio.value = zoomState?.minZoomRatio ?: 1f
            _maxZoomRatio.value = zoomState?.maxZoomRatio ?: 1f
            camera.cameraInfo.zoomState.observe(lifecycleOwner) { state ->
                _zoomRatio.value = state.zoomRatio
                _minZoomRatio.value = state.minZoomRatio
                _maxZoomRatio.value = state.maxZoomRatio
            }
            BindResult(camera, preview, capture, video)
        } catch (t: Throwable) {
            android.util.Log.e("RetroCam", "bindToLifecycle failed for ${target.persistenceKey} at fps=$fps", t)
            // Defensive cleanup before the caller's fps=0 retry attempt -
            // bindToLifecycle throwing shouldn't leave anything bound, but
            // make sure of it rather than risk the retry stacking on top.
            provider.unbindAll()
            null
        }
    }

    fun takePhoto(context: Context, onSaved: (Boolean) -> Unit) {
        val capture = imageCapture ?: return
        // Snapshot now, not when the async onImageSaved callback later
        // fires - the look actually in effect at the moment of capture is
        // the one that should get baked in, even if the user changes
        // recipe/settings in the brief window before the callback runs.
        val look = currentLook.get()
        val aspectRatio = captureAspectRatio.value
        playShutterSound()
        val name = "RetroCam_${timestamp()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/RetroCam")
        }
        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values,
        ).build()
        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    onSaved(true)
                    val uri = output.savedUri ?: return
                    _lastCaptureUri.value = uri
                    _lastCaptureIsVideo.value = false
                    // The photo file itself is already safely saved by
                    // ImageCapture at this point (onImageSaved already
                    // fired) - this is just the optional stamp/EXIF pass
                    // on top of it, so a failure here should never be
                    // allowed to crash the app and take an otherwise-fine
                    // photo down with it.
                    viewModelScope.launch {
                        runCatching { postProcessPhoto(context, uri, look, aspectRatio) }
                            .onFailure { android.util.Log.e("RetroCam", "postProcessPhoto failed for $uri", it) }
                    }
                }
                override fun onError(exception: ImageCaptureException) = onSaved(false)
            },
        )
    }

    private suspend fun postProcessPhoto(context: Context, uri: Uri, look: RenderLook, aspectRatio: CaptureAspectRatio) {
        val recipeDescription = recipes.value.firstOrNull { it.id == selectedRecipeId.value }
            ?.toDescription() ?: NO_FILTER_DESCRIPTION
        val stampSettings = dateStampSettings.value
        val stampMode = locationStampMode.value
        val location = if (locationEnabled.value) LocationProvider.lastKnownLocation(context) else null
        val locationText = when {
            location == null || stampMode == LocationStampMode.OFF -> null
            stampMode == LocationStampMode.COORDINATES -> formatCoordinates(location.latitude, location.longitude)
            stampMode == LocationStampMode.POSTAL_CODE -> LocationProvider.postalCode(context, location.latitude, location.longitude)
            else -> null
        }
        PhotoPostProcessor.process(context, uri, look, aspectRatio, recipeDescription, stampSettings, locationText, location)
    }

    fun startRecording(context: Context, onStateChanged: (Boolean) -> Unit) {
        // Guard on the recorder's own state, not the (async, callback-driven)
        // _isRecording StateFlow - a second tap can otherwise land before
        // VideoRecordEvent.Start has updated the UI, and CameraX throws
        // IllegalStateException on a genuinely concurrent start() call.
        if (recording != null) return
        val video = videoCapture ?: return
        // Snapshot now, same reasoning as takePhoto - the look active at
        // the moment recording starts is what the whole clip gets baked
        // with afterward (one consistent "film stock" per clip, not a
        // live per-frame value that could otherwise drift if settings
        // changed mid-recording).
        val look = currentLook.get()
        val name = "RetroCam_${timestamp()}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/RetroCam")
        }
        val outputOptions = MediaStoreOutputOptions.Builder(
            context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        ).setContentValues(values).build()

        playShutterSound()
        _recordingElapsedMs.value = 0L
        recording = video.output.prepareRecording(context, outputOptions)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(context)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> { _isRecording.value = true; onStateChanged(true) }
                    is VideoRecordEvent.Status ->
                        _recordingElapsedMs.value = event.recordingStats.recordedDurationNanos / 1_000_000
                    is VideoRecordEvent.Finalize -> {
                        _isRecording.value = false
                        _recordingElapsedMs.value = 0L
                        onStateChanged(false)
                        if (event.hasError().not()) {
                            val uri = event.outputResults.outputUri
                            _lastCaptureUri.value = uri
                            _lastCaptureIsVideo.value = true
                            // Same non-fatal-failure principle as the photo
                            // path: a failure here should never crash the
                            // app or take the already-saved raw recording
                            // down with it - VideoPostProcessor itself
                            // never touches the original file's bytes
                            // until a fully baked replacement exists.
                            viewModelScope.launch {
                                runCatching { VideoPostProcessor.process(context, uri, look) }
                                    .onFailure { android.util.Log.e("RetroCam", "VideoPostProcessor failed for $uri", it) }
                            }
                        }
                    }
                    else -> {}
                }
            }
    }

    fun stopRecording() {
        playShutterSound()
        recording?.stop()
        recording = null
    }

    private fun playShutterSound() {
        shutterSoundPlayer.play(shutterSound.value)
    }

    private fun timestamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(java.util.Date())

    override fun onCleared() {
        shutterSoundPlayer.release()
    }
}
