package com.retrocam.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.retrocam.app.camera.CameraViewModel
import com.retrocam.app.ui.RetroCamRoot
import com.retrocam.app.ui.theme.RetroBlack
import com.retrocam.app.ui.theme.RetroCamTheme

private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

class MainActivity : ComponentActivity() {

    private val viewModel: CameraViewModel by viewModels()

    private fun hasAllPermissions(): Boolean = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* no-op: the ON_RESUME observer in RetroCamPermissionGate re-checks state */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!hasAllPermissions()) requestPermissions.launch(REQUIRED_PERMISSIONS)

        setContent {
            RetroCamTheme {
                var hasPermission by remember { mutableStateOf(hasAllPermissions()) }
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) hasPermission = hasAllPermissions()
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                if (hasPermission) {
                    RetroCamRoot(viewModel)
                } else {
                    Box(Modifier.fillMaxSize().background(RetroBlack), contentAlignment = Alignment.Center) {
                        Text(
                            "RetroCam braucht Kamera- und Mikrofonzugriff.",
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                }
            }
        }
    }
}
