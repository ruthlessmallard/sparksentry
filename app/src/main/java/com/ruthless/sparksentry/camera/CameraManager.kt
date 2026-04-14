package com.ruthless.sparksentry.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.ruthless.sparksentry.fire.FireDetector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * CameraManager handles CameraX setup and frame capture for fire detection
 * 
 * Detection logic:
 * 1. Capture baseline image ("this is what normal looks like")
 * 2. Compare new frames to baseline
 * 3. Two-phase detection: SENTRY (slow) → CONFIRM (fast) → ALARM
 * 4. Alert only if fire persists through confirmation phase
 */
class CameraManager(
    private val context: android.content.Context,
    private val lifecycleOwner: LifecycleOwner,
    private val onFrameAnalyzed: (Bitmap, FireDetector.DetectionResult, DetectionPhase) -> Unit
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private val fireDetector = FireDetector()
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    
    // Baseline for comparison (what "normal" looks like)
    private var baselineFirePixelCount: Int = 0
    private var hasBaseline: Boolean = false
    
    // Detection phases
    enum class DetectionPhase {
        IDLE,      // Not monitoring
        CALIBRATING, // Capturing baseline
        SENTRY,    // Slow check every 15s
        CONFIRM,   // Fast check, confirming fire
        ALARM      // Fire confirmed, alarm active
    }
    
    private var currentPhase = DetectionPhase.IDLE
    private var frameCounter = 0
    private var confirmationFrames = 0
    private const val CONFIRMATION_THRESHOLD = 5 // Need 5 consecutive frames to alarm
    
    // Sensitivity threshold (0-100, default 50) - HIGHER = MORE SENSITIVE
    private var sensitivityThreshold = 50
    
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning
    
    /**
     * Set sensitivity (0-100)
     * 0-20: Low sensitivity (large fires only)
     * 21-40: Moderate-low
     * 41-60: Default
     * 61-80: Moderate-high  
     * 81-100: High sensitivity (catches small fires)
     */
    fun setSensitivity(threshold: Int) {
        sensitivityThreshold = threshold
    }
    
    fun getCurrentPhase(): DetectionPhase = currentPhase
    
    /**
     * Start calibration - captures next frame as baseline
     */
    fun startCalibration() {
        hasBaseline = false
        baselineFirePixelCount = 0
        currentPhase = DetectionPhase.CALIBRATING
        Log.d(TAG, "Starting calibration...")
    }
    
    /**
     * Start monitoring from sentry mode
     */
    fun startMonitoring() {
        if (!hasBaseline) {
            Log.w(TAG, "Cannot start monitoring without baseline!")
            return
        }
        currentPhase = DetectionPhase.SENTRY
        frameCounter = 0
        confirmationFrames = 0
        Log.d(TAG, "Starting monitoring from baseline: $baselineFirePixelCount")
    }
    
    fun stopMonitoring() {
        currentPhase = DetectionPhase.IDLE
        frameCounter = 0
        confirmationFrames = 0
    }
    
    fun hasCapturedBaseline(): Boolean = hasBaseline
    fun getBaselineCount(): Int = baselineFirePixelCount
    
    fun startCamera(surfaceProvider: Preview.SurfaceProvider, onError: (Exception) -> Unit) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases(surfaceProvider)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start camera", e)
                onError(e)
            }
        }, ContextCompat.getMainExecutor(context))
    }
    
    private fun bindCameraUseCases(surfaceProvider: Preview.SurfaceProvider) {
        val provider = cameraProvider ?: return
        
        // Preview use case
        val preview = Preview.Builder()
            .build()
            .also { it.setSurfaceProvider(surfaceProvider) }
        
        // Image analysis use case - analyze every frame at ~15fps
        imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    processImage(imageProxy)
                }
            }
        
        // Select back camera
        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        
        try {
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageAnalysis
            )
            _isRunning.value = true
        } catch (e: Exception) {
            Log.e(TAG, "Use case binding failed", e)
        }
    }
    
    private fun processImage(imageProxy: ImageProxy) {
        try {
            val bitmap = imageProxy.toBitmap()
            if (bitmap != null) {
                val result = fireDetector.analyze(bitmap)
                
                when (currentPhase) {
                    DetectionPhase.CALIBRATING -> {
                        // Capture first frame as baseline
                        baselineFirePixelCount = result.firePixelCount
                        hasBaseline = true
                        currentPhase = DetectionPhase.IDLE
                        Log.d(TAG, "Baseline captured: $baselineFirePixelCount fire pixels")
                        onFrameAnalyzed(bitmap, result.copy(
                            isFireDetected = false,
                            confidence = 0
                        ), currentPhase)
                    }
                    
                    DetectionPhase.SENTRY -> {
                        // Check every ~15 frames (roughly 1 second at 15fps)
                        frameCounter++
                        if (frameCounter >= 15) {
                            frameCounter = 0
                            
                            if (isFireDetected(result)) {
                                // Move to confirmation phase
                                currentPhase = DetectionPhase.CONFIRM
                                confirmationFrames = 1
                                Log.d(TAG, "Fire suspected, entering confirm phase")
                                onFrameAnalyzed(bitmap, result.copy(
                                    isFireDetected = true,
                                    confidence = 20
                                ), currentPhase)
                            } else {
                                onFrameAnalyzed(bitmap, result.copy(
                                    isFireDetected = false,
                                    confidence = 0
                                ), currentPhase)
                            }
                        } else {
                            onFrameAnalyzed(bitmap, result.copy(
                                isFireDetected = false,
                                confidence = 0
                            ), currentPhase)
                        }
                    }
                    
                    DetectionPhase.CONFIRM -> {
                        // Rapid confirmation for 10 frames (about 0.7s at 15fps)
                        if (isFireDetected(result)) {
                            confirmationFrames++
                            val confidence = (confirmationFrames * 100) / CONFIRMATION_THRESHOLD
                            
                            if (confirmationFrames >= CONFIRMATION_THRESHOLD) {
                                // Fire confirmed!
                                currentPhase = DetectionPhase.ALARM
                                Log.d(TAG, "Fire confirmed! Alarm triggered.")
                                onFrameAnalyzed(bitmap, result.copy(
                                    isFireDetected = true,
                                    confidence = 100
                                ), currentPhase)
                            } else {
                                onFrameAnalyzed(bitmap, result.copy(
                                    isFireDetected = true,
                                    confidence = confidence
                                ), currentPhase)
                            }
                        } else {
                            // Fire disappeared, go back to sentry
                            Log.d(TAG, "Fire disappeared, returning to sentry")
                            currentPhase = DetectionPhase.SENTRY
                            frameCounter = 0
                            confirmationFrames = 0
                            onFrameAnalyzed(bitmap, result.copy(
                                isFireDetected = false,
                                confidence = 0
                            ), currentPhase)
                        }
                    }
                    
                    DetectionPhase.ALARM -> {
                        // Stay in alarm for auto-reset
                        onFrameAnalyzed(bitmap, result.copy(
                            isFireDetected = true,
                            confidence = 100
                        ), currentPhase)
                    }
                    
                    DetectionPhase.IDLE -> {
                        onFrameAnalyzed(bitmap, result.copy(
                            isFireDetected = false,
                            confidence = 0
                        ), currentPhase)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing image", e)
        } finally {
            imageProxy.close()
        }
    }
    
    /**
     * Check if current frame indicates fire based on sensitivity
     */
    private fun isFireDetected(result: FireDetector.DetectionResult): Boolean {
        // Calculate percentage increase from baseline
        val increaseFromBaseline = if (baselineFirePixelCount > 0) {
            ((result.firePixelCount - baselineFirePixelCount) * 100) / baselineFirePixelCount
        } else {
            // If baseline had 0 fire pixels, any fire pixels is significant
            if (result.firePixelCount > 0) 100 else 0
        }
        
        // Higher sensitivity = lower threshold needed
        val thresholdPercent = when (sensitivityThreshold) {
            in 0..20 -> 200   // Must be 200% increase
            in 21..40 -> 100  // Must be 100% increase
            in 41..60 -> 50   // Default: 50% increase
            in 61..80 -> 25   // 25% increase
            in 81..100 -> 10  // 10% increase
            else -> 50
        }
        
        return increaseFromBaseline >= thresholdPercent && 
               result.firePixelCount > baselineFirePixelCount
    }
    
    /**
     * Reset from alarm back to sentry mode
     */
    fun resetAlarm() {
        if (currentPhase == DetectionPhase.ALARM) {
            currentPhase = DetectionPhase.SENTRY
            frameCounter = 0
            confirmationFrames = 0
            Log.d(TAG, "Alarm reset, returning to sentry mode")
        }
    }
    
    fun stopCamera() {
        cameraProvider?.unbindAll()
        _isRunning.value = false
    }
    
    fun shutdown() {
        cameraExecutor.shutdown()
    }
    
    companion object {
        private const val TAG = "CameraManager"
    }
}

/**
 * Extension function to convert ImageProxy to Bitmap
 */
private fun ImageProxy.toBitmap(): Bitmap? {
    val yBuffer = planes[0].buffer
    val uBuffer = planes[1].buffer
    val vBuffer = planes[2].buffer
    
    val ySize = yBuffer.remaining()
    val uSize = uBuffer.remaining()
    val vSize = vBuffer.remaining()
    
    val nv21 = ByteArray(ySize + uSize + vSize)
    
    yBuffer.get(nv21, 0, ySize)
    vBuffer.get(nv21, ySize, vSize)
    uBuffer.get(nv21, ySize + vSize, uSize)
    
    val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
    val out = ByteArrayOutputStream()
    yuvImage.compressToJpeg(Rect(0, 0, width, height), 100, out)
    val imageBytes = out.toByteArray()
    
    return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
}
