package com.xfan.evenginesound

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * 前台服务 + MediaSession：承载 Oboe 声浪引擎，保证锁屏/后台/车机下持续播放，
 * 并让方向盘媒体键等能控制。音频走 Oboe 的 Shared 流，可与音乐共存（duck 而非独占）。
 */
class AudioService : Service() {
    private val binder = AudioBinder()
    private var mediaSession: MediaSession? = null
    private var running = false

    inner class AudioBinder : Binder() {
        fun getService(): AudioService = this@AudioService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        mediaSession = MediaSession(this, "EVEngine").apply { isActive = true }
    }

    fun startEngine(blend: Float, volume: Float) {
        if (running) return
        AudioEngine.setBlend(blend)
        AudioEngine.setVolume(volume)
        running = AudioEngine.start()
        if (running) startForeground(NOTIF_ID, buildNotification())
    }

    fun stopEngine() {
        if (!running) return
        AudioEngine.stop()
        running = false
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    fun updateState(state: EngineState) {
        if (running) AudioEngine.pushState(state)
    }

    fun setBlend(blend: Float) = AudioEngine.setBlend(blend)
    fun setVolume(volume: Float) = AudioEngine.setVolume(volume)
    fun isRunning() = running

    private fun createNotificationChannel() {
        val ch = NotificationChannel(
            CHANNEL_ID, "EV 声浪", NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("EV 声浪模拟器")
            .setContentText("声浪引擎运行中")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        stopEngine()
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    companion object {
        const val NOTIF_ID = 1001
        const val CHANNEL_ID = "ev_engine_channel"
    }
}
