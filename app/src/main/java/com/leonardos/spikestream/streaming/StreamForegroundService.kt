package com.leonardos.spikestream.streaming

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.leonardos.spikestream.R
import com.leonardos.spikestream.activities.MainActivity

class StreamForegroundService : Service() {

    private val notificationManager by lazy {
        getSystemService(NotificationManager::class.java)
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var isForegroundRunning = false
    private var currentTitle = DEFAULT_TITLE
    private var currentText = DEFAULT_TEXT

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopInternal()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START, ACTION_UPDATE, null -> {
                currentTitle = intent?.getStringExtra(EXTRA_TITLE) ?: currentTitle
                currentText = intent?.getStringExtra(EXTRA_TEXT) ?: currentText
                startOrUpdateForeground()
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopInternal()
        super.onDestroy()
    }

    private fun startOrUpdateForeground() {
        acquireWakeLock()

        val notification = buildNotification(currentTitle, currentText)
        val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }

        if (!isForegroundRunning) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, foregroundType)
            isForegroundRunning = true
        } else {
            notificationManager?.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun stopInternal() {
        isForegroundRunning = false
        releaseWakeLock()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        notificationManager?.cancel(NOTIFICATION_ID)
    }

    private fun buildNotification(title: String, text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val contentIntent = PendingIntent.getActivity(this, 0, openIntent, pendingFlags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = CHANNEL_DESCRIPTION
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }

        notificationManager?.createNotificationChannel(channel)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return

        val powerManager = getSystemService(PowerManager::class.java) ?: return
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) {
                runCatching { lock.release() }
            }
        }
        wakeLock = null
    }

    companion object {
        private const val CHANNEL_ID = "spikestream_live_stream"
        private const val CHANNEL_NAME = "Live stream"
        private const val CHANNEL_DESCRIPTION = "Keeps Spike Stream active while broadcasting."
        private const val WAKE_LOCK_TAG = "SpikeStream:LiveBroadcast"
        private const val NOTIFICATION_ID = 4201
        private const val DEFAULT_TITLE = "Spike Stream live"
        private const val DEFAULT_TEXT = "Preparing broadcast..."

        const val ACTION_START = "com.leonardos.spikestream.streaming.action.START"
        const val ACTION_UPDATE = "com.leonardos.spikestream.streaming.action.UPDATE"
        const val ACTION_STOP = "com.leonardos.spikestream.streaming.action.STOP"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_TEXT = "extra_text"

        fun start(context: Context, title: String, text: String) {
            enqueue(context, ACTION_START, title, text)
        }

        fun update(context: Context, title: String, text: String) {
            enqueue(context, ACTION_UPDATE, title, text)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, StreamForegroundService::class.java))
        }

        private fun enqueue(context: Context, action: String, title: String, text: String) {
            val intent = Intent(context, StreamForegroundService::class.java).apply {
                this.action = action
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_TEXT, text)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
