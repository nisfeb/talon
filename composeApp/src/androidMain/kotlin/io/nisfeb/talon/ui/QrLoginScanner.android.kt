package io.nisfeb.talon.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

/**
 * Composable wrapper around ZXing-android-embedded's [ScanContract].
 * Returns a `() -> Unit` trigger that opens the camera; [onResult] gets
 * the code's text, or null when the user cancels. What the text means
 * is the caller's business: a login URI, a group or an invite code.
 *
 * Pure FOSS: ZXing-android-embedded ships its own scanning Activity
 * + camera preview, no Google Play Services required. Works on
 * GrapheneOS, LineageOS-without-GApps, and any other degoogled
 * Android. The scanner Activity declares its camera permission in
 * its own manifest entries (merged by AGP), so users see the system
 * permission prompt the first time they tap Scan.
 */
@Composable
actual fun rememberQrScanLauncher(prompt: String, onResult: (String?) -> Unit): (() -> Unit)? {
    val launcher = rememberLauncherForActivityResult(ScanContract()) { result -> onResult(result.contents) }
    return remember(launcher, prompt) {
        {
            val options = ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                // Allow rotation — code printed on a card / displayed
                // on another device might not be perfectly upright.
                setOrientationLocked(false)
                setBeepEnabled(false)
                // No need to capture the scanned image — we only want
                // the decoded payload.
                setBarcodeImageEnabled(false)
                setPrompt(prompt)
            }
            launcher.launch(options)
        }
    }
}
