# Changelog

All notable changes to RetroCam are documented here. Pre-1.0 — expect breaking changes and open issues; see each release's "Known issues" for what's still unresolved.

## [0.4.1] - 2026-09-23

### Fixed
- **Cinematic Look LUTs (Digital to Film, Modern 35mm) rendered flat and/or too dark.** CineColor's LUTs are professional grading LUTs designed for already-flat/log camera footage, so their own neutral black/white points weren't anchored to true 0/1 (verified: digital_to_film's black was lifted to ~0.05 and white capped at ~0.90-0.92; modern_35mm's white capped at ~0.92-0.93) - applied directly to an already-graded sRGB photo, this compressed the usable contrast range. Fixed by normalizing each LUT's own black/white points back to true 0/1 per channel before baking to the texture (preserves the actual color grade/curve shape, just reclaims full contrast). Verified on-device.

### Changed
- **Landscape: the live image now fills the full screen height.** Previously letterboxed to leave a separate reserved strip for the filter/recipe picker below it; that strip is now overlaid directly on the image's own bottom edge instead. Also shrank the reserved right-side sidebar (220dp → 170dp) since it read as too dominant relative to what the shutter/aspect-ratio controls actually need - the image renders wider as a result. Applies to both photo and video mode.

## [0.4.0] - 2026-09-23

### Added
- **Cinematic Look: real 3D-LUT color grading**, selectable independently of the Fuji-recipe-style parametric looks (Einstellungen → Cinematic Look). Four bundled looks - Digital to Film, Modern 35mm, Vintage, Bleach Bypass - each a real `.cube` 3D color-cube LUT (the DaVinci Resolve/Adobe standard, free downloads from [cinecolor.io](https://cinecolor.io/)) baked into a 2D tiled GL texture and sampled in the shader, at an adjustable strength slider, combinable with any recipe/film stock or "Kein Filter". Works for both photo (`PhotoLookBaker`) and video (`LookRenderer`).
- Studied how OldRoll and 8mm Vintage Camera (iOS) structure their own filter looks (architecture only - a layered LUT/overlay + blend-mode stack, independent of any parametric controls) to inform this feature's design; no assets or code were taken from either app.

### Changed
- Verified the new LUT shader math (`ShaderSource.applyLut`) end-to-end on-device against an offline Python reference render of the same source photo - confirmed pixel-for-pixel matching color output, including that no G-axis flip is needed in the 2D-tiled LUT sampling.

## [0.3.0] - 2026-09-23

### Added
- **Aspect ratio picker (4:3 / 16:9 / 1:1)** for photos, next to the shutter button. Applied as a center-crop in post-processing (works uniformly regardless of the raw capture resolution/aspect, and covers 1:1, which CameraX's own resolution selector can't do natively).
- **Recipe editor now uses sliders** for every numeric parameter (WB shift, highlight, shadow, color, sharpness, high ISO NR, clarity) instead of typing numbers in text fields, each with Fuji's own real range and step granularity.
- The recipe editor's **Name field is now pinned above the scrolling parameter list** instead of buried as just another field in it - easy to miss before.

### Changed
- **The recipe/film-stock picker moved out of the right-side black area into a horizontally swipeable strip directly under the live image** (landscape), with a black fade-out on both edges hinting there's more to scroll to. The right-side area now only holds the shutter, mode toggle, thumbnail, and aspect ratio picker.
- **Fixed a real color-accuracy bug in how recipes are rendered**: `Recipe.toRenderLook()` only read the White Balance *shift* (the R/B numbers), never the White Balance *setting* itself (e.g. "6600K", "Auto (Ambience Priority)") - but for several of the built-in recipes (transcribed from fujixweekly.com), most of the intended warmth actually comes from that base setting, not the shift on top of it. Verified numerically on-device (a warmth metric on a fixed test scene, before/after): Ektachrome 320T went from essentially zero measurable warmth to a clearly positive value, matching its source description ("warm", "amber and golden"); Portra 800 and Ektar 100 saw similar corrections. This is a deliberate approximation for a non-Fuji sensor and color pipeline, not a color-science match - still expect to keep tuning it against real photos.
- README now links to GitHub Releases and CHANGELOG.md near the top (previously only reachable by knowing to look at the repo's Releases tab).

### Fixed
- Replaced two README screenshots that inadvertently showed readable content on the test device's monitor in the background - re-shot against a scene with no screen content visible.

## [0.2.2] - 2026-09-23

### Changed
- **The viewfinder no longer fills the screen edge-to-edge.** 0.2.1 fixed *what resolution* Preview negotiated (16:9 instead of 4:3), but `PreviewView` still filled the entire, wider-than-16:9 screen and cropped the now-correct image to do it. Now the preview is sized to its own exact aspect ratio and letterboxed against the edge opposite the controls (left in landscape, top in portrait) - the freed space becomes real black background, and the recipe/film-stock chips, shutter, and mode toggle now live fully inside that reserved area instead of floating over the live image. Zero cropping anywhere now, in either orientation.

## [0.2.1] - 2026-09-23

### Fixed
- **Live viewfinder was visibly "zoomed in"** compared to the stock camera app - `Preview` was being pulled into a 4:3 stream (matching `ImageCapture`'s own aspect) by CameraX's default resolution selection, then center-crop-scaled to fill the much wider landscape screen, cropping away roughly 40% of the vertical field of view. Fixed by giving `Preview` its own explicit 16:9-targeted resolution selector, decoupled from whichever capture type is active.
- Reworked camera binding so only one of `ImageCapture`/`VideoCapture` is ever bound at a time, matching the active photo/video mode (switching modes now triggers a quick rebind) - closer to how a stock camera app behaves, and removes a class of aspect-ratio mismatch between all three use cases being bound together at once.
- Investigated a "grain isn't applied to video anymore" report and found it was a Settings state (the global Filmkorn/grain override was set to "Aus"), not a code bug - confirmed grain renders correctly on video once set to Standard/Heavy.

### Investigated, not fixed
- The photo/video resolution cap (see README) - ruled out several more possible causes this round, including confirming directly via the camera's own `CameraCharacteristics` that the missing resolution genuinely is available at the Camera2/HAL level for the exact camera this app uses, and confirming the stock camera app's alternate CameraX backend (`camera-camera2-pipe`) isn't currently possible to adopt here - its CameraX-facing integration layer isn't published to the public Maven repo. Still open.

## [0.2.0] - 2026-09-23

### Changed — architecture rework: look is baked in after capture, not live

The live GL filter pipeline (`CameraEffect`/`SurfaceProcessor`) has been removed entirely. Previously, every frame (preview, photo, video) was rendered through the retro-look shader live, so the viewfinder always matched the final capture exactly. That pipeline turned out to be silently capping `ImageCapture`'s resolution well below the sensor's real capability. The look is now applied once, right after capture, against the camera's own full-resolution output — the same way a real viewfinder/rangefinder camera's optical finder never showed you the exact exposed film either.

- **Photos**: a new offscreen GL pass (`PhotoLookBaker`) bakes the look into the captured JPEG at its own full resolution, reusing the exact same shader math the old live pipeline used.
- **Video**: a new decode → GL regrade → encode → mux pipeline (`VideoLookBaker`) applies the look to the recorded file afterward, frame by frame, with the original audio track copied through untouched. Video quality was also bumped from a fixed FHD cap to `Quality.HIGHEST`.
- Both paths follow the same safety principle already used for photo stamping: the original captured file is never touched until a complete, valid replacement exists, so a failure in the bake/transcode step can't corrupt or lose the original capture.

### Fixed
- Two real bugs found while building the video pipeline: a hardware video-codec resource-contention failure right after recording stops (now falls back to a software decoder), and recorded video coming out upside-down after grading (a video decoder's `SurfaceTexture` transform, unlike a camera's, can already fold in the container's rotation — reapplying it a second time double-rotated the result).
- `CameraX` upgraded 1.5.3 → 1.6.2 (with the accompanying AGP/Gradle/compileSdk bumps this required) — tried specifically to chase the resolution cap below, didn't fix it, kept anyway as a worthwhile update on its own merits.

### Known issues (not yet resolved)
- **Photo/video resolution is still capped below the sensor's real capability** (measured: 3264×2448 vs. the stock camera app's 4080×3072 on the test device) — extensively investigated this release (ruled out the live effect, `ResolutionSelector` strategies, capture mode, and the CameraX version itself as causes) but the actual root cause is still unknown. See the README's "Photo & video resolution" section for the full investigation.
- **The camera-switch crash** (a native `SIGSEGV` some users could hit switching cameras) most likely had its root cause removed as a side effect of this release's architecture change (there's no longer a live `SurfaceProcessor` for a camera switch to race), but this has **not yet been re-verified** with the stress-test protocol that originally characterized the bug. Treat as probably-but-not-confirmed fixed.
- OldRoll filter port is still blocked/in progress (see README).

## [0.1.0] - 2026-09-23

Initial public release. Real scanned film grain (not procedural noise) with Photoshop-style blend modes, Fuji-recipe-style photo color grading, gauge-based video film-stock presets, a configurable LED-style date/location stamp, live device-rotation handling, a lightweight in-app photo editor, and flash/torch support. At this point the retro look was still applied live via a custom GL `CameraEffect`, so preview/photo/video always matched exactly — see 0.2.0 for why that changed.
