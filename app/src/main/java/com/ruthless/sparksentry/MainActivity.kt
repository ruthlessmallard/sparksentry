package com.ruthless.sparksentry

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.ruthless.sparksentry.ui.screens.LiabilityScreen
import com.ruthless.sparksentry.ui.screens.MonitoringScreen
import com.ruthless.sparksentry.ui.theme.SparkSentryTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SparkSentryTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SparkSentryApp()
                }
            }
        }
    }
}

@Composable
fun SparkSentryApp() {
    var hasAcceptedLiability by remember { mutableStateOf(false) }
    
    if (!hasAcceptedLiability) {
        LiabilityScreen(
            onAccept = { hasAcceptedLiability = true }
        )
    } else {
        MonitoringScreen()
    }
}
