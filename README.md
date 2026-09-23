# RetroCam

An Android camera app that renders a real film look live, in the viewfinder, instead of applying a filter after the fact.

## Why this exists

Most "retro filter" camera apps apply a color-grade preset to a digital-clean capture after the shutter fires — you're editing a photo, not shooting film. RetroCam tries the opposite approach: a custom OpenGL pipeline sits between the camera sensor and every output (preview, photo, video), so grain, color science, halation, and softness are baked into the actual capture, and what you see in the viewfinder is what you get. It leans specifically on:

- **Real scanned film grain**, not procedural noise — actual extracted grain plates (5 gauges × standard/heavy tiers), not a random-noise shader trying to fake the look.
- **Fuji-recipe-style color grading** for photos (film simulation, dynamic range, white balance shift, highlight/shadow, color chrome effect, grain, clarity — the same parameter set X-series shooters already know from "recipe" culture), and **gauge-based film-stock presets** for video (8mm through 35mm, each with its own grain/color/softness character).
- **A date-back-style stamp**, burned into the pixel data like a real point-and-shoot's LED date back, right down to choosing how the LEDs' light actually composites onto the film underneath (see Blend modes below).

It's a personal project, built and iterated on with a physical test device rather than an emulator, prioritizing "does this look and feel like a real camera" over API completeness.

## Features

### Photo recipes
Fuji-style recipes (film simulation, dynamic range, white balance shift, highlight/shadow rolloff, color, sharpness, high-ISO NR, clarity, grain strength/size, color chrome effect + FX blue) drive a render pipeline (`RenderLook.kt`) that maps these into shader parameters. Built-in recipes ship seeded; users can create/edit their own in the recipe editor.

### Video film stocks
Gauge-based presets (8mm, 16mm, Super 16, 35mm, Super 35) with their own grain intensity/size, warmth, saturation, vignette, highlight rolloff, shadow lift, and softness — tuned so gauge order tracks grain intensity descending (coarser gauge = more grain), which is also what the picker sorts by.

### Real film grain
Grain isn't generated — it's sampled from real scanned film grain plates (5 gauges × 2 tiers × 6 frames each, extracted from real 4K scans, high-pass filtered and contrast-boosted, stored as grayscale PNGs in `app/src/main/assets/grain/`). A global **Aus/Standard/Heavy** override in Settings lets the grain tier be picked directly instead of only being derived from the active recipe/stock, and applies uniformly to both photo and video.

### Date/location stamp with Photoshop-style blend modes
A burned-in date and/or location stamp, styled after real camera date-backs (7-segment or dot-matrix LED look, configurable order/separator/year-style/color/corner/typeface). The stamp's blend mode against the photo underneath is configurable — Normal, Darken, Multiply, Lighten, Screen, Linear Dodge (Add), Overlay — the same idea as a Photoshop layer blend mode, so e.g. Linear Dodge gives the stamp a genuinely additive "glowing LED" look instead of just sitting flat on top of the image.

### Flash / torch
A single torch toggle (not per-shot flash) that works the same way for photo and video, only shown when the active camera actually reports a flash unit.

### Live rotation handling
The camera preview and captures track the phone's actual physical orientation (via a raw sensor listener, not just Configuration's coarse portrait/landscape), rebinding the camera pipeline as needed so photos and video come out in the orientation the phone was actually held in.

### Lightweight in-app photo editor
Tapping the last-capture thumbnail (for a photo, not a video) opens a small iOS-Photos-style editor instead of the system viewer: 90° rotate, a draggable/resizable crop rect with aspect-ratio presets (Frei/1:1/4:5/3:4/16:9), and brightness/contrast/saturation sliders with a live preview, saved back over the same file. Not a general editor - no layers, no undo history, just the handful of corrections people actually reach for right after a shot (cancel out and reopen to start over).

## Architecture

- **CameraX** (`androidx.camera`) for camera lifecycle/use-case management (Preview, ImageCapture, VideoCapture).
- **A custom `CameraEffect`/`SurfaceProcessor`** (`RetroCamSurfaceProcessor.kt`) intercepts the camera's OES texture stream on a dedicated GL thread and renders every frame through a fragment shader (`ShaderSource.kt` via `LookRenderer.kt`) that applies warmth/tint/saturation/contrast/highlight-rolloff/shadow-lift/grain/softness/vignette — the same shader pass drives preview, photo capture, and video capture, so all three actually match.
- **Room** (`RetroCamDatabase.kt`) for user-editable recipes and film stocks, seeded from JSON (`assets/seed/`) on first run.
- **DataStore** (`SettingsRepository`/`Settings.kt`) for app-wide settings (grain override, date/location stamp config, shutter sound, softness, selected camera/recipe/stock, etc).
- **Jetpack Compose** for the whole UI (`ui/`).
- The date/location stamp is burned in as a *post-processing* pass (`PhotoPostProcessor.kt`) after the photo file is already saved, separate from the live GL pipeline — it needs full `Canvas`/`Paint` control (blend modes, blur mask filters for the LED glow) that the shader pipeline doesn't give it.

## Known issues / open work

### Camera-switch crash (open, deep-dived, not solved)
Switching cameras (front/back/any lens) can crash the app with a native `SIGSEGV` (`libgui.so ConsumerBase::abandon()`, called from `SurfaceTexture.release()`, null pointer, fault addr `0x80`). Root cause: a race between this app's GL surface teardown/rebuild (required on every camera switch, since the effect pipeline owns its own `SurfaceTexture`/EGL context) and the camera HAL's own native teardown of the same underlying object.

What's been tried, in order:
1. Removed a forced/timed `SurfaceTexture.release()` fallback that was racing CameraX's own "done with this surface" callback — measurably reduced crash frequency.
2. Serialized rebinds (generation-counter guard) so two overlapping camera-switch rebinds can't stack.
3. Disabled camera-switch UI while actively recording (the originally-reported exact scenario is now impossible).
4. Upgraded CameraX 1.4.0 → 1.5.3, which ships a fix for "crash when effect is being activated after SurfaceProcessor is shut down" (`b/414150174`) — a very similar-sounding upstream bug. Real improvement on its own merits, but did **not** eliminate this specific crash; post-upgrade the crashing thread is CameraX's own internal GL thread, not this app's, suggesting the remaining race may live inside the library itself.
5. Added a 2-second UI lock after any rebind (camera/recipe/film-stock chips disabled, not just dimmed) to prevent immediate further interaction during the fragile teardown window.
6. Tried forcing `PreviewView` into `COMPATIBLE` (TextureView) mode instead of the default `PERFORMANCE` (SurfaceView) mode, reasoning TextureView's View-owned surface lifecycle might be less prone to the race. No improvement; reverted.

None of the above fully closes it — a controlled test of *purely repeated camera switches alone*, with no other interaction, still crashes occasionally. This looks like a genuine, still-live native concurrency bug in the Android camera/graphics stack under this exact architecture (a custom live GL effect pipeline + frequent camera switches), not something fixable purely at the app level. The real remaining lever is architectural: stop tearing down and rebuilding the GL/EGL/SurfaceProcessor pipeline on every camera switch. Treated as a known residual risk for now rather than something to keep patching reactively.

### OldRoll filter port (in progress)
Porting a set of filter looks from the OldRoll app into RetroCam's recipe system, replacing/supplementing the existing Fuji-recipe-style presets — blocked on getting a look at OldRoll's actual filter list (not installed on the current test device).

## Asset licensing note

The bundled real film grain plates (`app/src/main/assets/grain/`) were extracted from grain scans sourced from tdcat.com. Confirm your own rights/license before redistributing this repository or its assets further.

## Building

```
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`minSdk` 26, built and tested primarily on a physical device (not the emulator) — the whole point of the app is how it actually looks through a real sensor.
