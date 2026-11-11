package com.soundbaropti.livesub

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.DynamicsProcessing
import android.os.Build
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

class LiveSubPassThroughService : Service() {

    companion object {
        const val ACTION_START = "com.soundbaropti.livesub.ACTION_START"
        const val ACTION_STOP = "com.soundbaropti.livesub.ACTION_STOP"
        const val ACTION_ATTACH_SESSION = "com.soundbaropti.livesub.ACTION_ATTACH_SESSION"
        const val ACTION_DETACH_SESSION = "com.soundbaropti.livesub.ACTION_DETACH_SESSION"
        const val EXTRA_SESSION_ID = "extra_session_id"
        const val EXTRA_PACKAGE_NAME = "extra_package_name"

        @Volatile var isRunning = false
        @Volatile var targetBassOverMidsDb = 4.0f
        @Volatile var maxSubCutDb = 10.0f

        @Volatile var liveSubDb = 40.0f
        @Volatile var liveMidDb = 40.0f
        @Volatile var liveCutDb = 0.0f

        val activePackages = ConcurrentHashMap<Int, String>()
    }

    private val dspProcessors = ConcurrentHashMap<Int, DynamicsProcessing>()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopGuard()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_ATTACH_SESSION -> {
                val sid = intent.getIntExtra(EXTRA_SESSION_ID, -1)
                val pkg = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: "Media App"
                if (sid > 0) {
                    activePackages[sid] = pkg
                    if (isRunning) attachDspToSession(sid)
                }
            }
            ACTION_DETACH_SESSION -> {
                val sid = intent.getIntExtra(EXTRA_SESSION_ID, -1)
                if (sid > 0) {
                    activePackages.remove(sid)
                    detachDspFromSession(sid)
                }
            }
            else -> {
                if (!isRunning) {
                    startForegroundNotification()
                    isRunning = true
                    // Attach to Global Output Mix (session 0) AND any already-active Spotify/YTMusic sessions
                    attachDspToSession(0)
                    for (sid in activePackages.keys) {
                        attachDspToSession(sid)
                    }
                    startMicLoop()
                }
            }
        }
        return START_STICKY
    }

    private fun startForegroundNotification() {
        val channelId = "live_sub_guard_channel"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            channelId,
            "Live Woofer Guard (Spotify & Android Auto)",
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(channel)

        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(this, channelId)
            .setContentTitle("Live Woofer Auto-Guard Active")
            .setContentText("Monitoring cabin bass via mic for Spotify / Android Auto")
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(101, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(101, notification)
        }
    }

    private fun attachDspToSession(sessionId: Int) {
        if (dspProcessors.containsKey(sessionId)) return
        try {
            val config = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                2,
                true, 5,
                false, 0,
                false, 0,
                true
            ).build()

            val dp = DynamicsProcessing(0, sessionId, config)
            val cutoffs = floatArrayOf(55.0f, 95.0f, 400.0f, 2500.0f, 16000.0f)
            for (i in 0 until 5) {
                dp.setPreEqBandAllChannelsTo(i, DynamicsProcessing.EqBand(true, cutoffs[i], 0.0f))
            }
            dp.enabled = true
            dspProcessors[sessionId] = dp
        } catch (_: Exception) {
            // Ignore if OEM blocks session 0; individual app sessions still attach
        }
    }

    private fun detachDspFromSession(sessionId: Int) {
        try {
            dspProcessors.remove(sessionId)?.release()
        } catch (_: Exception) {}
    }

    private fun applySubCutToAllSessions(cutDb: Float) {
        for ((_, dp) in dspProcessors) {
            try {
                // Band 0 (<= 55 Hz) gets full cut; Band 1 (55-95 Hz) gets 70% of cut; Vocals/Highs stay 0 dB
                dp.setPreEqBandAllChannelsTo(0, DynamicsProcessing.EqBand(true, 55.0f, cutDb))
                dp.setPreEqBandAllChannelsTo(1, DynamicsProcessing.EqBand(true, 95.0f, cutDb * 0.7f))
            } catch (_: Exception) {}
        }
    }

    private fun startMicLoop() {
        thread(start = true, name = "LiveSubMicThread") {
            val sampleRate = 16000
            val blockSize = 1024
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            val recorder = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    max(minBuf, blockSize * 2)
                )
            } catch (_: SecurityException) {
                isRunning = false
                return@thread
            }

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                isRunning = false
                return@thread
            }

            val buf = ShortArray(blockSize)
            var subLp = 0.0f
            var midLp = 0.0f
            val alphaSub = 0.034f // ~90 Hz low-pass @ 16 kHz
            val alphaMid = 0.24f  // ~750 Hz low-pass @ 16 kHz

            recorder.startRecording()
            while (isRunning) {
                val read = recorder.read(buf, 0, blockSize)
                if (read <= 0) continue

                var subSum = 0.0
                var midSum = 0.0
                for (i in 0 until read) {
                    val x = buf[i] / 32768.0f
                    subLp += alphaSub * (x - subLp)
                    midLp += alphaMid * (x - midLp)
                    val midBand = midLp - subLp
                    subSum += subLp * subLp
                    midSum += midBand * midBand
                }

                val subDb = 10.0f * log10((subSum / read + 1e-12).toFloat()) + 94.0f
                val midDb = 10.0f * log10((midSum / read + 1e-12).toFloat()) + 94.0f

                liveSubDb = subDb
                liveMidDb = midDb

                val excessDb = (subDb - midDb) - targetBassOverMidsDb
                liveCutDb = if (subDb > 50.0f && excessDb > 0.0f) {
                    val desired = -min(maxSubCutDb, excessDb * 0.85f)
                    liveCutDb * 0.65f + desired * 0.35f
                } else {
                    val released = liveCutDb * 0.90f
                    if (abs(released) < 0.1f) 0.0f else released
                }

                applySubCutToAllSessions(liveCutDb)
            }

            try {
                recorder.stop()
                recorder.release()
            } catch (_: Exception) {}
        }
    }

    private fun stopGuard() {
        isRunning = false
        liveCutDb = 0.0f
        for ((_, dp) in dspProcessors) {
            try {
                dp.setPreEqBandAllChannelsTo(0, DynamicsProcessing.EqBand(true, 55.0f, 0.0f))
                dp.setPreEqBandAllChannelsTo(1, DynamicsProcessing.EqBand(true, 95.0f, 0.0f))
                dp.release()
            } catch (_: Exception) {}
        }
        dspProcessors.clear()
    }

    override fun onDestroy() {
        stopGuard()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}
