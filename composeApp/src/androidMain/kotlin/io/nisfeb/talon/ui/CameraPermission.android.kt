package io.nisfeb.talon.ui

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/** The system prompt, mirroring the mic gate in TalonApp. */
@Composable
actual fun rememberCameraPermission(): CameraPermission {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { result ->
        granted = result
        // After "Don't ask again" Android suppresses the dialog and
        // answers false straight away, so without this the camera
        // button is a silent no-op on every tap.
        if (!result) {
            Toast.makeText(
                context,
                "Camera permission denied — enable it in Settings",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }
    return remember(granted) {
        object : CameraPermission {
            override val granted = granted
            override fun request() = launcher.launch(Manifest.permission.CAMERA)
        }
    }
}
