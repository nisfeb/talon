package io.nisfeb.talon.ui

/**
 * The longest side an image Talon takes in keeps, pasted or picked.
 * Plenty for a chat, and a full-size copy of a very large one is what
 * ran a desktop app out of memory: a 20000 px square is 1.6 GB as
 * pixels, a 48 MP photo 192 MB.
 */
internal const val MAX_IMAGE_SIDE = 4096

/** [width] by [height] scaled to fit within [maxSide] on its longest side, aspect kept; as is when it fits. */
internal fun fitWithin(width: Int, height: Int, maxSide: Int = MAX_IMAGE_SIDE): Pair<Int, Int> {
    val longest = maxOf(width, height)
    if (longest <= maxSide) return width to height
    val scale = maxSide.toDouble() / longest
    return (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
}
