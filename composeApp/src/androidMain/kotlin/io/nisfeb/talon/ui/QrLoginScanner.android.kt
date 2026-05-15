package io.nisfeb.talon.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import io.nisfeb.talon.login.TalonLoginUri

/**
 * Composable wrapper around ZXing-android-embedded's [ScanContract].
 * Returns a `() -> Unit` trigger the LoginScreen invokes when the user
 * taps "Scan QR". On scan completion, invokes [onResult] with the
 * parsed [TalonLoginUri.Payload] — or null when:
 *   - the user cancels the scanner
 *   - the QR isn't a `talon://login?...` URI
 *
 * Pure FOSS: ZXing-android-embedded ships its own scanning Activity
 * + camera preview, no Google Play Services required. Works on
 * GrapheneOS, LineageOS-without-GApps, and any other degoogled
 * Android. The scanner Activity declares its camera permission in
 * its own manifest entries (merged by AGP), so users see the system
 * permission prompt the first time they tap Scan.
 */
@Composable
fun rememberQrLoginScanLauncher(
    onResult: (TalonLoginUri.Payload?) -> Unit,
): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val raw = result.contents
        val payload = raw?.let(TalonLoginUri::decode)
        onResult(payload)
    }
    return remember(launcher) {
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
                setPrompt("Scan a Talon login QR")
            }
            launcher.launch(options)
        }
    }
}
