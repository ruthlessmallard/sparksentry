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
 * 3. Alert only if fire pixels increase significantly from baseline
 */
class CameraManager(
    private val context: android.content.Context,
    private val lifecycleOwner: LifecycleOwner,
    private val onFrameAnalyzed: (Bitmap, FireDetector.DetectionResult) -> Unit
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private val fireDetector = FireDetector()
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    
    // Baseline for comparison (what "normal" looks like)
    private var baselineFirePixelCount: Int = 0
    private var hasBaseline: Boolean = false
    
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
    
    /**
     * Capture current frame as baseline for comparison
     */
    fun captureBaseline() {
        hasBaseline = false
        // Next frame processed will be captured as baseline
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
        
        // Image analysis use case
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
                
                // If no baseline, capture this as baseline
                if (!hasBaseline) {
                    baselineFirePixelCount = result.firePixelCount
                    hasBaseline = true
                    Log.d(TAG, "Baseline captured: $baselineFirePixelCount fire pixels")
                    onFrameAnalyzed(bitmap, result.copy(
                        isFireDetected = false,
                        confidence = 0
                    ))
                    return
                }
                
                // Calculate percentage increase from baseline
                val increaseFromBaseline = if (baselineFirePixelCount > 0) {
                    ((result.firePixelCount - baselineFirePixelCount) * 100) / baselineFirePixelCount
                } else {
                    // If baseline had 0 fire pixels, any fire pixels is significant
                    if (result.firePixelCount > 0) 100 else 0
                }
                
                // Determine if this is a fire based on sensitivity setting
                // Higher sensitivity = lower threshold needed
                val thresholdPercent = when (sensitivityThreshold) {
                    in 0..20 -> 200   // Must be 200% increase (large fires only)
                    in 21..40 -> 100  // Must be 100% increase
                    in 41..60 -> 50   // Default: 50% increase
                    in 61..80 -> 25   // 25% increase
                    in 81..100 -> 10  // 10% increase (very sensitive)
                    else -> 50
                }
                
                val isFire = increaseFromBaseline >= thresholdPercent && result.firePixelCount > baselineFirePixelCount
                
                val adjustedResult = result.copy(
                    isFireDetected = isFire,
                    confidence = if (isFire) minOf(increaseFromBaseline, 100) else 0
                )
                
                onFrameAnalyzed(bitmap, adjustedResult)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing image", e)
        } finally {
            imageProxy.close()
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
