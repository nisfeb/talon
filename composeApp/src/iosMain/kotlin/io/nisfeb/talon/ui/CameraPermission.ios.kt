package io.nisfeb.talon.ui

import androidx.compose.runtime.Composable

/**
 * iOS prompts on first capture, from AVFoundation, so there is
 * nothing to ask here. A refusal surfaces as the capturer failing to
 * start, which [io.nisfeb.talon.call.CallEngine.setCameraEnabled]
 * already reports.
 */
@Composable
actual fun rememberCameraPermission(): CameraPermission = CameraPermission.Granted
