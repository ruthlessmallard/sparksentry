package com.ruthless.sparksentry

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * SparkSentry Monitoring Service
 * 
 * Two-phase fire detection:
 * 1. SENTRY: Check every 15 seconds for fire colors
 * 2. CONFIRM: If detected, check every 1 second for 10 seconds
 * 3. ALARM: If still detected, trigger full alarm
 */
class MonitoringService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    
    private var monitoringJob: Job? = null
    private var mediaPlayer: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioManager: AudioManager? = null
    
    private var currentState = MonitoringState.IDLE
    private var confirmationCount = 0
    
    enum class MonitoringState {
        IDLE, SENTRY, CONFIRM, ALARM
    }
    
    interface MonitoringCallback {
        fun onStateChanged(state: MonitoringState)
        fun onFireDetected(confidence: Int)
    }
    
    private var callback: MonitoringCallback? = null
    
    inner class LocalBinder : Binder() {
        fun getService(): MonitoringService = this@MonitoringService
    }
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    
    override fun onBind(intent: Intent): IBinder {
        return binder
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        return START_STICKY
    }
    
    fun setCallback(callback: MonitoringCallback) {
        this.callback = callback
    }
    
    fun startMonitoring() {
        if (monitoringJob?.isActive == true) return
        
        currentState = MonitoringState.SENTRY
        callback?.onStateChanged(currentState)
        
        monitoringJob = serviceScope.launch {
            while (true) {
                when (currentState) {
                    MonitoringState.SENTRY -> {
                        delay(SENTRY_INTERVAL_MS)
                        if (detectFire()) {
                            enterConfirmMode()
                        }
                    }
                    MonitoringState.CONFIRM -> {
                        delay(CONFIRM_INTERVAL_MS)
                        if (detectFire()) {
                            confirmationCount++
                            callback?.onFireDetected(confirmationCount * 10)
                            if (confirmationCount >= CONFIRMATION_THRESHOLD) {
                                triggerAlarm()
                            }
                        } else {
                            returnToSentryMode()
                        }
                    }
                    MonitoringState.ALARM -> {
                        delay(ALARM_DURATION_MS)
                        returnToSentryMode()
                    }
                    else -> delay(1000)
                }
            }
        }
    }
    
    fun stopMonitoring() {
        monitoringJob?.cancel()
        currentState = MonitoringState.IDLE
        confirmationCount = 0
        stopAlarm()
        callback?.onStateChanged(currentState)
    }
    
    private fun enterConfirmMode() {
        currentState = MonitoringState.CONFIRM
        confirmationCount = 0
        playWarningChirp()
        callback?.onStateChanged(currentState)
    }
    
    private fun returnToSentryMode() {
        currentState = MonitoringState.SENTRY
        confirmationCount = 0
        stopAlarm()
        callback?.onStateChanged(currentState)
    }
    
    private fun triggerAlarm() {
        currentState = MonitoringState.ALARM
        callback?.onStateChanged(currentState)
        
        // Maximum volume alarm
        playAlarmSound()
        
        // Vibrate if available
        vibrate()
        
        // Pause other audio (music, podcasts)
        requestAudioFocus()
    }
    
    private fun playWarningChirp() {
        // Short warning tone
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer.create(this, R.raw.chirp).apply {
                setVolume(0.7f, 0.7f)
                start()
            }
        } catch (e: Exception) {
            // Fallback to system beep
        }
    }
    
    private fun playAlarmSound() {
        try {
            // Set max volume
            audioManager?.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15,
                0
            )
            
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer.create(this, R.raw.alarm).apply {
                isLooping = true
                setVolume(1.0f, 1.0f)
                start()
            }
        } catch (e: Exception) {
            // Fallback
        }
    }
    
    private fun stopAlarm() {
        mediaPlayer?.apply {
            if (isPlaying) stop()
            release()
        }
        mediaPlayer = null
        abandonAudioFocus()
    }
    
    private fun vibrate() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(1000, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(1000)
        }
    }
    
    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .build()
            audioManager?.requestAudioFocus(focusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager?.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
        }
    }
    
    private fun abandonAudioFocus() {
        // Audio focus automatically abandoned when media player stops
    }
    
    /**
     * Fire detection using color analysis
     * Analyzes camera frame for orange/red/yellow fire colors
     * Excludes blue-white welding arc
     */
    private fun detectFire(): Boolean {
        // TODO: Implement actual camera frame analysis
        // This is a placeholder that simulates detection for testing
        
        // In real implementation:
        // 1. Capture frame from CameraX
        // 2. Convert to HSV color space
        // 3. Check for orange/red/yellow pixel clusters
        // 4. Exclude blue-white (welding arc)
        // 5. Return true if fire-colored pixels exceed threshold
        
        return false // Placeholder
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SparkSentry Monitoring",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background fire monitoring"
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SparkSentry")
            .setContentText("Fire monitoring active")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
    
    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SparkSentry::MonitoringWakeLock"
        ).apply {
            acquire(10 * 60 * 1000L) // 10 minutes max
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        monitoringJob?.cancel()
        serviceScope.cancel()
        stopAlarm()
        wakeLock?.release()
    }
    
    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "sparksentry_monitoring"
        
        private const val SENTRY_INTERVAL_MS = 15000L // 15 seconds
        private const val CONFIRM_INTERVAL_MS = 1000L // 1 second
        private const val CONFIRMATION_THRESHOLD = 10  // 10 seconds of confirmation
        private const val ALARM_DURATION_MS = 10000L   // 10 second alarm
    }
}
