# Changelog

All notable changes to RetroCam are documented here. Pre-1.0 — expect breaking changes and open issues; see each release's "Known issues" for what's still unresolved.

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
