package dev.openrune.cache.extractor.osrs.map

import kotlin.math.ceil
import kotlin.math.pow

object JagexColor {
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
}