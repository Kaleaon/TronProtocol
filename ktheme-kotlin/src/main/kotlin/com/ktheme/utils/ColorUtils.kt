package com.ktheme.utils

import com.ktheme.models.RGBColor
import com.ktheme.models.RGBAColor

/**
 * Color utilities for Ktheme
 * Provides color conversion, manipulation, and validation
 */
object ColorUtils {
    
    /**
     * Convert hex color to RGB
     */
    fun hexToRgb(hex: String): RGBColor {
        val cleanHex = hex.removePrefix("#")
        require(cleanHex.length == 6) { "Invalid hex color: $hex" }
        
        return RGBColor(
            r = cleanHex.substring(0, 2).toInt(16),
            g = cleanHex.substring(2, 4).toInt(16),
            b = cleanHex.substring(4, 6).toInt(16)
        )
    }
    
    /**
     * Convert RGB to hex
     */
    fun rgbToHex(rgb: RGBColor): String {
        return "#${rgb.r.toString(16).padStart(2, '0')}" +
               "${rgb.g.toString(16).padStart(2, '0')}" +
               "${rgb.b.toString(16).padStart(2, '0')}"
    }
    
    /**
     * Convert hex color to RGBA
     */
    fun hexToRgba(hex: String, alpha: Float = 1f): RGBAColor {
        val rgb = hexToRgb(hex)
        return RGBAColor(rgb.r, rgb.g, rgb.b, alpha)
    }
    
    /**
     * Convert RGBA to hex (with alpha)
     */
    fun rgbaToHex(rgba: RGBAColor): String {
        val alphaHex = (rgba.a * 255).toInt().toString(16).padStart(2, '0')
        return rgbToHex(RGBColor(rgba.r, rgba.g, rgba.b)) + alphaHex
    }
    
    /**
     * Darken a color by a percentage
     */
    fun darken(hex: String, percent: Float): String {
        val rgb = hexToRgb(hex)
        val factor = 1 - percent / 100
        return rgbToHex(
            RGBColor(
                r = (rgb.r * factor).toInt().coerceAtLeast(0),
                g = (rgb.g * factor).toInt().coerceAtLeast(0),
                b = (rgb.b * factor).toInt().coerceAtLeast(0)
            )
        )
    }
    
    /**
     * Lighten a color by a percentage
     */
    fun lighten(hex: String, percent: Float): String {
        val rgb = hexToRgb(hex)
        val factor = percent / 100
        return rgbToHex(
            RGBColor(
                r = (rgb.r + (255 - rgb.r) * factor).toInt().coerceAtMost(255),
                g = (rgb.g + (255 - rgb.g) * factor).toInt().coerceAtMost(255),
                b = (rgb.b + (255 - rgb.b) * factor).toInt().coerceAtMost(255)
            )
        )
    }
    
    /**
     * Mix two colors
     */
    fun mix(color1: String, color2: String, weight: Float = 0.5f): String {
        val rgb1 = hexToRgb(color1)
        val rgb2 = hexToRgb(color2)
        
        return rgbToHex(
            RGBColor(
                r = (rgb1.r * (1 - weight) + rgb2.r * weight).toInt(),
                g = (rgb1.g * (1 - weight) + rgb2.g * weight).toInt(),
                b = (rgb1.b * (1 - weight) + rgb2.b * weight).toInt()
            )
        )
    }
    
    /**
     * Get contrast color (black or white) for a background
     */
    fun getContrastColor(backgroundColor: String): String {
        val rgb = hexToRgb(backgroundColor)
        // Calculate relative luminance
        val luminance = (0.299 * rgb.r + 0.587 * rgb.g + 0.114 * rgb.b) / 255
        return if (luminance > 0.5) "#000000" else "#FFFFFF"
    }
    
    /**
     * Validate if a string is a valid hex color
     */
    fun isValidHex(hex: String): Boolean {
        val cleanHex = hex.removePrefix("#")
        return cleanHex.matches(Regex("^[a-fA-F0-9]{3}$|^[a-fA-F0-9]{6}$|^[a-fA-F0-9]{8}$"))
    }
    
    /**
     * Parse Android color int to hex
     */
    fun colorIntToHex(color: Int): String {
        return String.format("#%06X", 0xFFFFFF and color)
    }
    
    /**
     * Parse hex to Android color int
     */
    fun hexToColorInt(hex: String): Int {
        val cleanHex = hex.removePrefix("#")
        return when (cleanHex.length) {
            6 -> ("FF$cleanHex").toLong(16).toInt()
            8 -> cleanHex.toLong(16).toInt()
            else -> throw IllegalArgumentException("Invalid hex color: $hex")
        }
    }
}
