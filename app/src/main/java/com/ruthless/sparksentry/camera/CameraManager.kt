package com.ruthless.sparksentry.camera

import android.content.Context
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
 */
class CameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val onFrameAnalyzed: (Bitmap, FireDetector.DetectionResult) -> Unit
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private val fireDetector = FireDetector()
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    
    // Sensitivity threshold (0-100, default 50)
    private var sensitivityThreshold = 50
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning
    
    fun setSensitivity(threshold: Int) {
        sensitivityThreshold = threshold
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
            // Unbind all use cases before rebinding
            provider.unbindAll()
            
            // Bind use cases to camera
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
                // Adjust detection threshold based on sensitivity slider
                // Lower sensitivity = higher threshold needed to trigger
                val adjustedThreshold = when (sensitivityThreshold) {
                    in 0..20 -> 5   // Very sensitive
                    in 21..40 -> 4
                    in 41..60 -> 3  // Default
                    in 61..80 -> 2
                    else -> 1       // Less sensitive
                }
                
                val result = fireDetector.analyze(bitmap)
                
                // Apply sensitivity adjustment
                val adjustedResult = if (result.firePixelCount >= adjustedThreshold) {
                    result.copy(isFireDetected = true)
                } else {
                    result.copy(isFireDetected = false)
                }
                
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
