package com.ruthless.sparksentry.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ruthless.sparksentry.R
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
    var state by remember { mutableStateOf(MonitoringState.IDLE) }
    
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
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // Status indicator
        StatusIndicator(state = state)
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // Camera preview placeholder
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Camera Preview\n(Set phone on tripod and frame work area)",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
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
                        onClick = { state = MonitoringState.BASELINE_SET }
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
            }
        }
    }
}

@Composable
fun StatusIndicator(state: MonitoringState) {
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
            "Checking fire..."
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
    color: androidx.compose.ui.graphics.Color,
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
fun StatusBadge(text: String, color: androidx.compose.ui.graphics.Color) {
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
