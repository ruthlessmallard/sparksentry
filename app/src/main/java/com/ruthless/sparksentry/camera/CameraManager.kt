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
 * Simple CameraManager - direct color detection without baseline
 * 
 * Detection logic:
 * - Analyze every ~15 frames (roughly 1 second)
 * - If fire colors exceed threshold, enter confirm mode
 * - Confirm for 5 consecutive frames before alarm
 */
class CameraManager(
    private val context: android.content.Context,
    private val lifecycleOwner: LifecycleOwner,
    private val onFrameAnalyzed: (Bitmap, DetectionState, Int) -> Unit
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private val fireDetector = FireDetector()
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    
    enum class DetectionState {
        IDLE,       // Not monitoring
        MONITORING, // Active monitoring
        CONFIRM,    // Possible fire detected
        ALARM       // Fire confirmed
    }
    
    private var currentState = DetectionState.IDLE
    private var frameCounter = 0
    private var confirmFrames = 0
    private var sensitivityThreshold = 50 // 0-100, higher = more sensitive
    
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning
    
    fun setSensitivity(value: Int) {
        sensitivityThreshold = value
    }
    
    fun startMonitoring() {
        currentState = DetectionState.MONITORING
        frameCounter = 0
        confirmFrames = 0
    }
    
    fun stopMonitoring() {
        currentState = DetectionState.IDLE
        frameCounter = 0
        confirmFrames = 0
    }
    
    fun resetAlarm() {
        if (currentState == DetectionState.ALARM) {
            currentState = DetectionState.MONITORING
            confirmFrames = 0
        }
    }
    
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
        
        val preview = Preview.Builder()
            .build()
            .also { it.setSurfaceProvider(surfaceProvider) }
        
        imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    processImage(imageProxy)
                }
            }
        
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
                
                when (currentState) {
                    DetectionState.MONITORING -> {
                        frameCounter++
                        if (frameCounter >= 15) { // Check every ~15 frames = ~1 second at 15fps
                            frameCounter = 0
                            
                            if (isFireDetected(result)) {
                                currentState = DetectionState.CONFIRM
                                confirmFrames = 1
                                val confidence = (confirmFrames * 100) / CONFIRMATION_THRESHOLD
                                onFrameAnalyzed(bitmap, currentState, confidence)
                            } else {
                                onFrameAnalyzed(bitmap, currentState, 0)
                            }
                        } else {
                            onFrameAnalyzed(bitmap, currentState, 0)
                        }
                    }
                    
                    DetectionState.CONFIRM -> {
                        if (isFireDetected(result)) {
                            confirmFrames++
                            val confidence = (confirmFrames * 100) / CONFIRMATION_THRESHOLD
                            
                            if (confirmFrames >= CONFIRMATION_THRESHOLD) {
                                currentState = DetectionState.ALARM
                                onFrameAnalyzed(bitmap, currentState, 100)
                            } else {
                                onFrameAnalyzed(bitmap, currentState, confidence)
                            }
                        } else {
                            // Fire disappeared, back to monitoring
                            currentState = DetectionState.MONITORING
                            confirmFrames = 0
                            onFrameAnalyzed(bitmap, currentState, 0)
                        }
                    }
                    
                    DetectionState.ALARM -> {
                        // Stay in alarm until reset
                        onFrameAnalyzed(bitmap, currentState, 100)
                    }
                    
                    DetectionState.IDLE -> {
                        onFrameAnalyzed(bitmap, currentState, 0)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing image", e)
        } finally {
            imageProxy.close()
        }
    }
    
    private fun isFireDetected(result: FireDetector.DetectionResult): Boolean {
        // Adjust threshold based on sensitivity
        // Higher sensitivity = lower fire pixel threshold needed
        val requiredFirePixels = when (sensitivityThreshold) {
            in 0..20 -> 5    // Low sensitivity - lots of fire pixels needed
            in 21..40 -> 4
            in 41..60 -> 3   // Default
            in 61..80 -> 2
            in 81..100 -> 1  // High sensitivity - single pixel can trigger
            else -> 3
        }
        
        return result.firePixelCount >= requiredFirePixels
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
        private const val CONFIRMATION_THRESHOLD = 5
    }
}

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
