package io.nisfeb.talon.util

import kotlin.math.abs
import kotlin.math.round

/**
 * Multiplatform replacement for `"%.Nf".format(x)` — Kotlin/Native has no
 * `String.format` (the JVM leaned on `java.lang.String.format`). Rounds
 * half-away-from-zero to [digits] fractional places and always emits
 * exactly [digits] fractional characters.
 *
 * ponytail: scales through a Long, so it's only exact for values whose
 * `abs * 10^digits` fits in a Long (~9.2e18). Every caller here formats
 * coordinates, percentages, and file sizes — all far inside that range.
 * Tie-breaking may differ from the JVM's HALF_UP in the last place; it's
 * display-only text, so that's immaterial.
 */
fun Double.formatDecimals(digits: Int): String {
    if (isNaN()) return "NaN"
    if (isInfinite()) return if (this > 0) "Infinity" else "-Infinity"
    var factor = 1L
    repeat(digits) { factor *= 10 }
    val scaled = round(abs(this) * factor).toLong()
    val intPart = scaled / factor
    val fracPart = scaled % factor
    val sign = if (this < 0 && scaled != 0L) "-" else ""
    if (digits == 0) return "$sign$intPart"
    return "$sign$intPart.${fracPart.toString().padStart(digits, '0')}"
}
