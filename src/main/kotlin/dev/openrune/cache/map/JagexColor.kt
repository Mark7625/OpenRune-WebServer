package dev.openrune.cache.map

import kotlin.math.pow
import kotlin.math.roundToInt

object JagexColor {
    /**
     * Convert Jagex 16-bit packed HSL (6 hue, 3 sat, 7 lightness) to hex string e.g. "0xaaaaaa".
     * Uses the same algorithm as the website ColorUtils (unpackJagexHSL + convertHSLToHex).
     */
    fun jagexPacked16ToHexString(packed: Int): String {
        val lightness = packed and 0x7F           // 7 bits
        val saturation = (packed shr 7) and 0x7  // 3 bits
        val hue = (packed shr 10) and 0x3F       // 6 bits
        val h = (hue / 63.0) * 360.0
        val s = (saturation / 7.0) * 100.0
        val l = (lightness / 127.0) * 100.0
        val chroma = (1.0 - kotlin.math.abs(2.0 * (l / 100.0) - 1.0)) * (s / 100.0)
        val x = chroma * (1.0 - kotlin.math.abs((h / 60.0) % 2.0 - 1.0))
        val m = (l / 100.0) - chroma / 2.0
        val (r, g, b) = when {
            h < 60  -> triple(chroma + m, x + m, m)
            h < 120 -> triple(x + m, chroma + m, m)
            h < 180 -> triple(m, chroma + m, x + m)
            h < 240 -> triple(m, x + m, chroma + m)
            h < 300 -> triple(x + m, m, chroma + m)
            else    -> triple(chroma + m, m, x + m)
        }
        val ri = (r * 255).roundToInt().coerceIn(0, 255)
        val gi = (g * 255).roundToInt().coerceIn(0, 255)
        val bi = (b * 255).roundToInt().coerceIn(0, 255)
        return "0x%02x%02x%02x".format(ri, gi, bi)
    }

    private fun triple(a: Double, b: Double, c: Double) = Triple(a, b, c)
    const val BRIGHTNESS_MAX = 0.6
    const val BRIGHTNESS_HIGH = 0.7
    const val BRIGHTNESS_LOW = 0.8
    const val BRIGHTNESS_MIN = 0.9

    private const val HUE_OFFSET = 0.5 / 64.0
    private const val SATURATION_OFFSET = 0.5 / 8.0

    fun packHSL(hue: Int, saturation: Int, luminance: Int): Short {
        return (((hue and 63) shl 10) or ((saturation and 7) shl 7) or (luminance and 127)).toShort()
    }

    fun packHSLFull(hue: Int, saturation: Int, luminance: Int): Int {
        return ((hue and 0xFF) shl 16) or ((saturation and 0xFF) shl 8) or (luminance and 0xFF)
    }

    fun unpackHue(hsl: Short): Int = (hsl.toInt() shr 10) and 63
    fun unpackSaturation(hsl: Short): Int = (hsl.toInt() shr 7) and 7
    fun unpackLuminance(hsl: Short): Int = hsl.toInt() and 127

    fun unpackHueFull(hsl: Int): Int = (hsl shr 16) and 0xFF
    fun unpackSaturationFull(hsl: Int): Int = (hsl shr 8) and 0xFF
    fun unpackLuminanceFull(hsl: Int): Int = hsl and 0xFF

    fun formatHSL(hsl: Short): String {
        return "%02Xh%Xs%02Xl".format(unpackHue(hsl), unpackSaturation(hsl), unpackLuminance(hsl))
    }

    fun HSLtoRGB(hsl: Short, brightness: Double): Int {
        val hue = unpackHue(hsl) / 64.0 + HUE_OFFSET
        val saturation = unpackSaturation(hsl) / 8.0 + SATURATION_OFFSET
        val luminance = unpackLuminance(hsl) / 128.0

        val chroma = (1.0 - kotlin.math.abs(2.0 * luminance - 1.0)) * saturation
        val x = chroma * (1.0 - kotlin.math.abs((hue * 6.0 % 2) - 1.0))
        val lightness = luminance - chroma / 2.0

        var r = lightness
        var g = lightness
        var b = lightness

        when ((hue * 6.0).toInt()) {
            0 -> { r += chroma; g += x }
            1 -> { g += chroma; r += x }
            2 -> { g += chroma; b += x }
            3 -> { b += chroma; g += x }
            4 -> { b += chroma; r += x }
            else -> { r += chroma; b += x }
        }

        var rgb = ((r * 256).toInt() shl 16) or ((g * 256).toInt() shl 8) or (b * 256).toInt()
        rgb = adjustForBrightness(rgb, brightness)
        if (rgb == 0) rgb = 1
        return rgb
    }

    fun HSLtoRGBFull(hsl: Int): Int {
        val hue = unpackHueFull(hsl) / 256.0
        val saturation = unpackSaturationFull(hsl) / 256.0
        val luminance = unpackLuminanceFull(hsl) / 256.0

        val chroma = (1.0 - kotlin.math.abs(2.0 * luminance - 1.0)) * saturation
        val x = chroma * (1.0 - kotlin.math.abs((hue * 6.0 % 2) - 1.0))
        val lightness = luminance - chroma / 2.0

        var r = lightness
        var g = lightness
        var b = lightness

        when ((hue * 6.0).toInt()) {
            0 -> { r += chroma; g += x }
            1 -> { g += chroma; r += x }
            2 -> { g += chroma; b += x }
            3 -> { b += chroma; g += x }
            4 -> { b += chroma; r += x }
            else -> { r += chroma; b += x }
        }

        var rgb = (((r * 256).toInt() and 255) shl 16) or
                (((g * 256).toInt() and 255) shl 8) or
                ((b * 256).toInt() and 255)
        if (rgb == 0) rgb = 1
        return rgb
    }

    fun adjustForBrightness(rgb: Int, brightness: Double): Int {
        var r = ((rgb shr 16) and 0xFF) / 256.0
        var g = ((rgb shr 8) and 0xFF) / 256.0
        var b = (rgb and 0xFF) / 256.0

        r = r.pow(brightness)
        g = g.pow(brightness)
        b = b.pow(brightness)

        return ((r * 256).toInt() shl 16) or ((g * 256).toInt() shl 8) or (b * 256).toInt()
    }

    fun createPalette(brightness: Double): IntArray {
        return IntArray(65536) { i -> HSLtoRGB(i.toShort(), brightness) }
    }

    fun getRGBFull(hsl: Int): Int = HSLtoRGBFull(hsl)

    /**
     * Parse hex string (0xRRGGBB or #RRGGBB) to Jagex 16-bit packed HSL (6 hue, 3 sat, 7 lightness).
     * Inverse of [jagexPacked16ToHexString] for use when reading diff config colour values.
     */
    fun hexStringToJagexPacked16(hex: String): Int {
        val trimmed = hex.trim()
        val hexClean = when {
            trimmed.startsWith("0x", ignoreCase = true) -> trimmed.drop(2)
            trimmed.startsWith("#") -> trimmed.drop(1)
            else -> trimmed
        }
        if (hexClean.length != 6) return 0
        val r = hexClean.substring(0, 2).toIntOrNull(16) ?: 0
        val g = hexClean.substring(2, 4).toIntOrNull(16) ?: 0
        val b = hexClean.substring(4, 6).toIntOrNull(16) ?: 0
        val (h, s, l) = rgbToHsl(r, g, b)
        val hue6 = (h * 63.0 / 360.0).toInt().coerceIn(0, 63)
        val sat3 = (s * 7.0 / 100.0).toInt().coerceIn(0, 7)
        val light7 = (l * 127.0 / 100.0).toInt().coerceIn(0, 127)
        return (hue6 shl 10) or (sat3 shl 7) or light7
    }

    private fun rgbToHsl(r: Int, g: Int, b: Int): Triple<Double, Double, Double> {
        val rr = r / 255.0
        val gg = g / 255.0
        val bb = b / 255.0
        val max = maxOf(rr, gg, bb)
        val min = minOf(rr, gg, bb)
        var h = 0.0
        var s = 0.0
        val l = (max + min) / 2.0
        if (max != min) {
            val d = max - min
            s = if (l > 0.5) d / (2.0 - max - min) else d / (max + min)
            h = when (max) {
                rr -> ((gg - bb) / d + (if (gg < bb) 6 else 0)) / 6.0
                gg -> ((bb - rr) / d + 2) / 6.0
                else -> ((rr - gg) / d + 4) / 6.0
            }
        }
        return Triple(h * 360.0, s * 100.0, l * 100.0)
    }
}