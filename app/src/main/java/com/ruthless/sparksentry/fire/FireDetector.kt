package com.ruthless.sparksentry.fire

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs

/**
 * Fire detection using HSV color analysis
 * 
 * Fire characteristics:
 * - Orange/Red/Yellow hues (15-45 degrees on HSV wheel)
 * - High saturation (bright colors, not muddy)
 * - Welding arc exclusion: Blue-white colors (180-260 degrees)
 */
class FireDetector {

    data class DetectionResult(
        val isFireDetected: Boolean,
        val confidence: Int, // 0-100
        val firePixelCount: Int,
        val totalPixelCount: Int
    )

    /**
     * Analyze a bitmap for fire colors
     * @param bitmap The image to analyze
     * @param sampleRate Skip pixels for performance (1 = every pixel, 4 = every 4th pixel)
     * @return Detection result with confidence score
     */
    fun analyze(bitmap: Bitmap, sampleRate: Int = 4): DetectionResult {
        val width = bitmap.width
        val height = bitmap.height
        var firePixelCount = 0
        var totalChecked = 0
        
        // Sample pixels for performance
        for (y in 0 until height step sampleRate) {
            for (x in 0 until width step sampleRate) {
                val pixel = bitmap.getPixel(x, y)
                
                if (isFireColor(pixel)) {
                    firePixelCount++
                }
                totalChecked++
            }
        }
        
        // Calculate fire percentage
        val firePercentage = if (totalChecked > 0) {
            (firePixelCount * 100) / totalChecked
        } else 0
        
        // Threshold: 2% of image must be fire-colored
        val isDetected = firePercentage >= FIRE_THRESHOLD_PERCENT
        
        // Confidence linearly scales from threshold to 10%
        val confidence = when {
            firePercentage >= 10 -> 100
            firePercentage >= FIRE_THRESHOLD_PERCENT -> {
                ((firePercentage - FIRE_THRESHOLD_PERCENT) * 100) / (10 - FIRE_THRESHOLD_PERCENT)
            }
            else -> 0
        }
        
        return DetectionResult(
            isFireDetected = isDetected,
            confidence = confidence,
            firePixelCount = firePixelCount,
            totalPixelCount = totalChecked
        )
    }
    
    /**
     * Check if a pixel represents fire (orange/red/yellow)
     * Excludes welding arc (blue-white)
     */
    private fun isFireColor(pixel: Int): Boolean {
        val hsv = FloatArray(3)
        Color.colorToHSV(pixel, hsv)
        
        val hue = hsv[0]        // 0-360 degrees
        val saturation = hsv[1] // 0-1
        val value = hsv[2]      // 0-1 (brightness)
        
        // Must be bright enough (not dark shadows)
        if (value < MIN_BRIGHTNESS) return false
        
        // Must be saturated (not grey/white/black)
        if (saturation < MIN_SATURATION) return false
        
        // Check fire color ranges
        // Red wraps around 360/0, so handle specially
        val isFireHue = isInFireHueRange(hue)
        
        // Exclude welding arc (blue-white)
        // Blue is around 200-260, but high saturation excludes most blue-white
        if (hue in WELDING_ARC_HUE_RANGE && saturation < 0.3f) {
            return false // Likely welding arc
        }
        
        return isFireHue
    }
    
    /**
     * Check if hue is in fire color range
     * Fire: Red (345-360), Orange-Red (15-30), Orange (30-45), Yellow-Orange (45-60)
     */
    private fun isInFireHueRange(hue: Float): Boolean {
        return hue >= FIRE_HUE_RED_LOW || 
               hue in FIRE_HUE_ORANGE_RED_START..FIRE_HUE_YELLOW_END
    }
    
    /**
     * Check if pixel is likely welding arc (blue-white, extremely bright)
     * This is a secondary filter for high-confidence exclusion
     */
    fun isWeldingArc(bitmap: Bitmap, x: Int, y: Int): Boolean {
        val pixel = bitmap.getPixel(x, y)
        val hsv = FloatArray(3)
        Color.colorToHSV(pixel, hsv)
        
        // Welding arc: very bright, blue tint, extreme saturation or lack thereof
        return hsv[2] > 0.95f && // Extremely bright
               hsv[0] in WELDING_ARC_HUE_RANGE
    }
    
    companion object {
        // Fire hue ranges (HSV)
        private const val FIRE_HUE_RED_LOW = 345f      // Deep red (wraps around)
        private const val FIRE_HUE_ORANGE_RED_START = 15f
        private const val FIRE_HUE_YELLOW_END = 60f    // Yellow-orange boundary
        
        // Welding arc exclusion
        private val WELDING_ARC_HUE_RANGE = 180f..260f // Blue range
        
        // Quality thresholds
        private const val MIN_BRIGHTNESS = 0.4f        // Value > 40%
        private const val MIN_SATURATION = 0.3f        // Saturation > 30%
        
        // Detection threshold
        private const val FIRE_THRESHOLD_PERCENT = 2   // 2% of image must be fire
    }
}

/**
 * Extension function to create a step range
 */
private infix fun Int.step(step: Int): IntProgression {
    return IntProgression.fromClosedRange(this, Int.MAX_VALUE, step)
}
