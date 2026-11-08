package com.memo.branding

import kotlin.math.pow

/** WCAG 2.2 contrast between two `#rrggbb` colours (research R21). */
object Contrast {
    private val HEX = Regex("^#[0-9a-fA-F]{6}$")

    fun isColour(value: String): Boolean = HEX.matches(value)

    /** The contrast ratio, from 1 to 21. */
    fun ratio(
        a: String,
        b: String,
    ): Double {
        val (lighter, darker) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (lighter + FLARE) / (darker + FLARE)
    }

    /** Relative luminance (https://www.w3.org/TR/WCAG22/#dfn-relative-luminance). */
    fun luminance(colour: String): Double {
        require(isColour(colour)) { "not a #rrggbb colour" }
        val channels = (0 until 3).map { colour.substring(1 + 2 * it, 3 + 2 * it).toInt(HEX_RADIX) / MAX_CHANNEL }
        val (r, g, b) = channels.map { if (it <= LINEAR_LIMIT) it / LINEAR_DIVISOR else ((it + OFFSET) / SCALE).pow(GAMMA) }
        return RED * r + GREEN * g + BLUE * b
    }

    private const val FLARE = 0.05
    private const val HEX_RADIX = 16
    private const val MAX_CHANNEL = 255.0
    private const val LINEAR_LIMIT = 0.04045
    private const val LINEAR_DIVISOR = 12.92
    private const val OFFSET = 0.055
    private const val SCALE = 1.055
    private const val GAMMA = 2.4
    private const val RED = 0.2126
    private const val GREEN = 0.7152
    private const val BLUE = 0.0722
}
