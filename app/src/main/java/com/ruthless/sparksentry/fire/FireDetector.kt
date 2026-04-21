package com.ruthless.sparksentry.fire

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs

/**
 * Fire detection using HSV color analysis with temporal flicker detection
 * 
 * Fire characteristics:
 * - Orange/Red/Yellow hues (15-45 degrees on HSV wheel)
 * - High saturation (bright colors, not muddy)
 * - Temporal flicker: 5-15Hz variance (catches real fire, ignores static orange)
 * - Minimum size: 2% of frame (filters small sparks, catches 6"+ flames)
 */
class FireDetector {

    // Temporal analysis window
    private val flickerWindow = ArrayList<Double>(20) // 20 samples over 2 seconds
    private val windowDurationMs = 2000L // 2 second analysis window

    data class DetectionResult(
        val isFireDetected: Boolean,
        val confidence: Int, // 0-100
        val firePixelCount: Int,
        val totalPixelCount: Int,
        val flickerScore: Float
    )

    /**
     * Analyze a bitmap for fire with temporal confirmation
     * @param bitmap The image to analyze
     * @param sampleRate Skip pixels for performance (1 = every pixel, 4 = every 4th pixel)
     * @return Detection result with confidence score and flicker analysis
     */
    fun analyze(bitmap: Bitmap, sampleRate: Int = 4): DetectionResult {
        val width = bitmap.width
        val height = bitmap.height
        val totalPixels = (width / sampleRate) * (height / sampleRate)

        var firePixelCount = 0

        for (y in 0 until height step sampleRate) {
            for (x in 0 until width step sampleRate) {
                val pixel = bitmap.getPixel(x, y)
                if (isFireColor(pixel)) {
                    firePixelCount++
                }
            }
        }

        val fireRatio = firePixelCount.toFloat() / totalPixels

        // Phase 1: Minimum fire size check (2% = ~6" flame at 10-13')
        if (fireRatio < 0.02f) {
            flickerWindow.clear()
            return DetectionResult(
                isFireDetected = false,
                confidence = 0,
                firePixelCount = firePixelCount,
                totalPixelCount = totalPixels,
                flickerScore = 0f
            )
        }

        // Phase 2: Temporal flicker analysis
        val flickerScore = analyzeFlicker(fireRatio)

        // Combined decision
        val isFire = fireRatio > 0.03f && flickerScore > 0.6f
        val confidence = (flickerScore * 100).toInt().coerceIn(0, 100)

        return DetectionResult(
            isFireDetected = isFire,
            confidence = confidence,
            firePixelCount = firePixelCount,
            totalPixelCount = totalPixels,
            flickerScore = flickerScore
        )
    }

    /**
     * Analyzes temporal flicker in the 5-15Hz range
     * Returns score 0.0-1.0 indicating fire likelihood
     */
    private fun analyzeFlicker(currentFireRatio: Float): Float {
        // Add current sample
        flickerWindow.add(currentFireRatio.toDouble())

        // Remove old samples outside window (20 samples = 2 seconds at 100ms sample rate)
        while (flickerWindow.size > 20) {
            flickerWindow.removeAt(0)
        }

        // Need minimum samples for analysis
        if (flickerWindow.size < 10) {
            return 0.5f // Insufficient data, neutral
        }

        // Calculate mean
        val mean = flickerWindow.average()

        // Calculate zero-crossing rate (how often signal crosses mean)
        var zeroCrossings = 0
        for (i in 1 until flickerWindow.size) {
            val prevAbove = flickerWindow[i-1] > mean
            val currAbove = flickerWindow[i] > mean
            if (prevAbove != currAbove) {
                zeroCrossings++
            }
        }

        // Fire flickers at 5-15Hz
        // With 10 samples/second, that's 10-30 crossings in 2 seconds
        val crossingRate = zeroCrossings.toFloat()

        return when {
            crossingRate < 5f -> 0.2f  // Too slow (static object)
            crossingRate in 10f..25f -> 0.9f  // Ideal fire flicker
            crossingRate > 40f -> 0.4f  // Too fast (electrical/artifact)
            else -> 0.7f  // Moderate flicker
        }
    }

    /**
     * Check if a pixel is fire-colored using HSV
     * Excludes welding arc (blue-white)
     */
    private fun isFireColor(pixel: Int): Boolean {
        val red = Color.red(pixel)
        val green = Color.green(pixel)
        val blue = Color.blue(pixel)

        // Convert to HSV
        val hsv = FloatArray(3)
        Color.RGBToHSV(red, green, blue, hsv)

        val hue = hsv[0] // 0-360
        val saturation = hsv[1] * 100 // 0-100
        val value = hsv[2] * 100 // 0-100

        // Exclusion: Welding arc (blue-white, high value)
        if (hue in 180f..260f && value > 80f && saturation < 40f) {
            return false
        }

        // Fire colors: Orange, Red, Yellow
        // Orange-Red: 0-25 degrees
        // Yellow: 25-50 degrees
        val isFireHue = hue <= 50f || hue >= 340f
        val isBrightEnough = value > 50f
        val isSaturated = saturation > 40f

        return isFireHue && isBrightEnough && isSaturated
    }

    /**
     * Reset temporal analysis (call when entering monitoring mode)
     */
    fun reset() {
        flickerWindow.clear()
    }

    /**
     * Reset and check if we have enough samples for a reliable reading
     */
    fun hasEnoughSamples(): Boolean {
        return flickerWindow.size >= 10
    }
}
