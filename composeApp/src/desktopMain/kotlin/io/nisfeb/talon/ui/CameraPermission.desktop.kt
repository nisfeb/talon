package io.nisfeb.talon.ui

import androidx.compose.runtime.Composable

/**
 * No runtime permission model on Linux or Windows; macOS prompts from
 * the system when capture starts. Either way there is nothing for the
 * app to ask, and a refusal shows up as the camera failing to open.
 */
@Composable
actual fun rememberCameraPermission(): CameraPermission = CameraPermission.Granted
