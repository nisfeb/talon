package io.nisfeb.talon.login

/**
 * Render arbitrary text content as a square QR-code bit matrix.
 * Both Android and Desktop back this with ZXing's `QRCodeWriter` —
 * the only platform-specific bit is the import, so the actuals are
 * tiny adapters around the JVM library. Output is a row-major
 * 2D boolean grid (true = dark cell, false = light), which
 * `QrCodeImage` then draws via Compose Canvas without any further
 * platform-specific bitmap conversion.
 *
 * Throws if the content is too large to fit in a single QR symbol
 * at error-correction level M. The caller (LoginQrShareScreen) bounds
 * inputs to ship URL + +code so practical content stays well within
 * QR's ~2KB binary capacity.
 */
expect object QrCodeGenerator {
    fun generate(content: String): Array<BooleanArray>
}
