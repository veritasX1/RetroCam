package com.retrocam.app.camera.gl

import androidx.camera.core.CameraEffect
import java.util.concurrent.Executor

/** CameraEffect's constructors are protected, so a trivial subclass is the
 * only way to instantiate one for our own SurfaceProcessor. */
class RetroCamCameraEffect(
    targets: Int,
    executor: Executor,
    surfaceProcessor: RetroCamSurfaceProcessor,
    errorListener: androidx.core.util.Consumer<Throwable>,
) : CameraEffect(targets, executor, surfaceProcessor, errorListener)
