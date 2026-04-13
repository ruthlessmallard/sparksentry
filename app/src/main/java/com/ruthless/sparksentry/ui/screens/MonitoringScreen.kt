package com.ruthless.sparksentry.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.ruthless.sparksentry.camera.CameraManager
import com.ruthless.sparksentry.fire.FireDetector
import com.ruthless.sparksentry.ui.theme.AlertOrange
import com.ruthless.sparksentry.ui.theme.SafetyYellow
import com.ruthless.sparksentry.ui.theme.SuccessGreen
import com.ruthless.sparksentry.ui.theme.ToolTruckRed

enum class MonitoringState {
    IDLE,           // No baseline set
    BASELINE_SET,   // Baseline captured, ready to monitor
    SENTRY,         // Active monitoring - slow check
    CONFIRM,        // Fire detected, rapid confirmation
    ALARM           // Confirmed fire - full alarm
}

@Composable
fun MonitoringScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    
    var state by remember { mutableStateOf(MonitoringState.IDLE) }
    var sensitivity by remember { mutableIntStateOf(50) }
    var detectionConfidence by remember { mutableIntStateOf(0) }
    var hasCameraPermission by remember { mutableStateOf(false) }
    
    // Check camera permission
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
    }
    
    LaunchedEffect(Unit) {
        hasCameraPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }
    
    // Camera manager
    val cameraManager = remember {
        CameraManager(
            context = context,
            lifecycleOwner = lifecycleOwner,
            onFrameAnalyzed = { bitmap, result ->
                if (result.isFireDetected) {
                    detectionConfidence = result.confidence
                    when (state) {
                        MonitoringState.SENTRY -> state = MonitoringState.CONFIRM
                        MonitoringState.CONFIRM -> {
                            if (result.confidence > 50) {
                                state = MonitoringState.ALARM
                            }
                        }
                        else -> {}
                    }
                } else {
                    if (state == MonitoringState.CONFIRM) {
                        state = MonitoringState.SENTRY
                    }
                }
            }
        )
    }
    
    DisposableEffect(Unit) {
        onDispose {
            cameraManager.shutdown()
        }
    }
    
    // Update sensitivity when slider changes
    LaunchedEffect(sensitivity) {
        cameraManager.setSensitivity(sensitivity)
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header
        Text(
            text = "SparkSentry",
            style = MaterialTheme.typography.headlineLarge,
            color = SafetyYellow,
            fontWeight = FontWeight.Bold
        )
        
        Text(
            text = "Personal Fire Watch",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Status indicator
        StatusIndicator(state = state, confidence = detectionConfidence)
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Camera preview
        if (hasCameraPermission) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                AndroidView(
                    factory = { ctx ->
                        PreviewView(ctx).apply {
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    update = { previewView ->
                        if (state != MonitoringState.IDLE) {
                            cameraManager.startCamera(
                                surfaceProvider = previewView.surfaceProvider,
                                onError = { /* Handle error */ }
                            )
                        } else {
                            cameraManager.stopCamera()
                        }
                    }
                )
                
                // Overlay hint text when idle
                if (state == MonitoringState.IDLE) {
                    Text(
                        text = "Camera Preview\n(Set phone on tripod and frame work area)",
                        modifier = Modifier.align(Alignment.Center),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Camera permission required\nGrant permission to continue",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Sensitivity slider (only show when monitoring or ready)
        if (state != MonitoringState.IDLE) {
            SensitivitySlider(
                sensitivity = sensitivity,
                onSensitivityChange = { sensitivity = it }
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
        
        // Action buttons
        when (state) {
            MonitoringState.IDLE -> {
                ActionButton(
                    text = "SET BASELINE",
                    color = SafetyYellow,
                    onClick = { state = MonitoringState.BASELINE_SET }
                )
            }
            MonitoringState.BASELINE_SET -> {
                ActionButton(
                    text = "START MONITORING",
                    color = SuccessGreen,
                    onClick = { state = MonitoringState.SENTRY }
                )
            }
            MonitoringState.SENTRY -> {
                Column {
                    StatusBadge(
                        text = "SENTRY MODE - Checking every 15s",
                        color = SuccessGreen
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    ActionButton(
                        text = "STOP MONITORING",
                        color = ToolTruckRed,
                        onClick = { 
                            state = MonitoringState.BASELINE_SET
                            cameraManager.stopCamera()
                        }
                    )
                }
            }
            MonitoringState.CONFIRM -> {
                StatusBadge(
                    text = "⚠️ FIRE DETECTED - Confirming...",
                    color = AlertOrange
                )
            }
            MonitoringState.ALARM -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ToolTruckRed, RoundedCornerShape(12.dp))
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "🔥 FIRE DETECTED 🔥",
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Auto-reset in 10 seconds...",
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f),
                            fontSize = 14.sp
                        )
                    }
                }
                
                // Auto-reset after 10 seconds
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(10000)
                    state = MonitoringState.SENTRY
                }
            }
        }
    }
}

@Composable
fun SensitivitySlider(
    sensitivity: Int,
    onSensitivityChange: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        Text(
            text = "Alert Sensitivity",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Less", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            Text(
                "$sensitivity%",
                fontWeight = FontWeight.Bold,
                color = SafetyYellow
            )
            Text("More", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
        
        Slider(
            value = sensitivity.toFloat(),
            onValueChange = { onSensitivityChange(it.toInt()) },
            valueRange = 0f..100f,
            colors = SliderDefaults.colors(
                thumbColor = SafetyYellow,
                activeTrackColor = SafetyYellow,
                inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
            )
        )
        
        Text(
            text = when (sensitivity) {
                in 0..20 -> "High sensitivity - may trigger on sparks"
                in 21..40 -> "Moderate-high sensitivity"
                in 41..60 -> "Default sensitivity"
                in 61..80 -> "Moderate-low sensitivity"
                else -> "Low sensitivity - only large fires"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}

@Composable
fun StatusIndicator(state: MonitoringState, confidence: Int) {
    val (color, text) = when (state) {
        MonitoringState.IDLE -> Pair(
            MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f),
            "Set baseline to begin"
        )
        MonitoringState.BASELINE_SET -> Pair(
            SafetyYellow,
            "Ready to monitor"
        )
        MonitoringState.SENTRY -> Pair(
            SuccessGreen,
            "Monitoring active"
        )
        MonitoringState.CONFIRM -> Pair(
            AlertOrange,
            "Checking fire... ${confidence}%"
        )
        MonitoringState.ALARM -> Pair(
            ToolTruckRed,
            "FIRE DETECTED"
        )
    }
    
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(color),
            contentAlignment = Alignment.Center
        ) {
            val icon = when (state) {
                MonitoringState.IDLE -> "📷"
                MonitoringState.BASELINE_SET -> "✓"
                MonitoringState.SENTRY -> "👁"
                MonitoringState.CONFIRM -> "⚠"
                MonitoringState.ALARM -> "🔥"
            }
            Text(text = icon, fontSize = 36.sp)
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = color
        )
    }
}

@Composable
fun ActionButton(
    text: String,
    color: Color,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color)
    ) {
        Text(
            text = text,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = if (color == SafetyYellow) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onPrimary
        )
    }
}

@Composable
fun StatusBadge(text: String, color: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = color,
            fontWeight = FontWeight.Medium,
            fontSize = 16.sp
        )
    }
}
