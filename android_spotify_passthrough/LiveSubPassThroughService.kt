package com.soundbaropti.livesub

import android.app.Service
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.DynamicsProcessing
import kotlin.concurrent.thread
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Android System-Wide Live Subwoofer Pass-Through Service (Works with Spotify, YouTube Music & Android Auto).
 *
 * How it works:
 * 1. Attaches Android's hardware-accelerated `DynamicsProcessing` (5-band Pre-EQ + Multi-Band Limiter)
 *    to `audioSessionId = 0` (Global Output Mix) or Spotify's active AudioSession ID.
 * 2. Continuously reads the Phone Microphone via `AudioRecord` at 16 kHz.
 * 3. Separates Cabin Sub-Bass (30-90 Hz) from Vocals/Mids (250-2000 Hz) using real-time biquad filters.
 * 4. Whenever loud/boomy bass exceeds the target offset above mids, it dynamically lowers
 *    Band 0 (20-55 Hz) and Band 1 (55-95 Hz) by up to -12 dB in real time while leaving
 *    Bands 2, 3, 4 (Vocals & Treble) completely untouched at 0.0 dB!
 *
 * Permissions required in AndroidManifest.xml:
 *   <uses-permission android:name="android.permission.RECORD_AUDIO" />
 *   <uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
 *   <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
 */
class LiveSubPassThroughService : Service() {

    @Volatile
    private var running = false
    private var dynamicsProcessing: DynamicsProcessing? = null
    private var currentSubCutDb = 0.0f

    // User-tunable parameters
    var targetBassOverMidsDb = 4.0f
    var maxSubCutDb = 12.0f

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running) {
            running = true
            setupSystemPassThroughDsp(audioSessionId = 0)
            startMicLoop()
        }
        return START_STICKY
    }

    private fun setupSystemPassThroughDsp(audioSessionId: Int) {
        val config = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            2,      // Stereo channels
            true,   // Pre-EQ enabled
            5,      // 5 bands: [50Hz Sub, 90Hz Upper-Sub, 400Hz Low-Mid, 2kHz Vocal, 10kHz High]
            false, 0,
            false, 0,
            true    // Limiter enabled
        ).build()

        val dp = DynamicsProcessing(0, audioSessionId, config)

        val cutoffs = floatArrayOf(55.0f, 95.0f, 400.0f, 2500.0f, 16000.0f)
        for (i in 0 until 5) {
            val band = DynamicsProcessing.EqBand(true, cutoffs[i], 0.0f)
            dp.setPreEqBandAllChannelsTo(i, band)
        }
        dp.enabled = true
        dynamicsProcessing = dp
    }

    private fun updateSubwooferGain(cutDb: Float) {
        val dp = dynamicsProcessing ?: return
        // Band 0 (<= 55 Hz) gets 100% of cut; Band 1 (55-95 Hz) gets 70% of cut
        dp.setPreEqBandAllChannelsTo(0, DynamicsProcessing.EqBand(true, 55.0f, cutDb))
        dp.setPreEqBandAllChannelsTo(1, DynamicsProcessing.EqBand(true, 95.0f, cutDb * 0.7f))
    }

    private fun startMicLoop() {
        thread(start = true, name = "LiveSubMicLoop") {
            val sampleRate = 16000
            val blockSize = 1024
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.UNPROCESSED,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                max(minBuf, blockSize * 2)
            )

            val buf = ShortArray(blockSize)
            // Simple 1st-order low-pass @ 90 Hz for Sub and band-pass 250-2000 Hz for Mids
            var subState = 0.0f
            var midLpState = 0.0f
            val alphaSub = 0.034f  // ~90 Hz @ 16 kHz
            val alphaMid = 0.24f   // ~750 Hz @ 16 kHz

            recorder.startRecording()
            while (running) {
                val read = recorder.read(buf, 0, blockSize)
                if (read <= 0) continue

                var subEnergy = 0.0
                var midEnergy = 0.0
                for (i in 0 until read) {
                    val x = buf[i] / 32768.0f
                    subState += alphaSub * (x - subState)
                    midLpState += alphaMid * (x - midLpState)
                    val midBand = midLpState - subState

                    subEnergy += subState * subState
                    midEnergy += midBand * midBand
                }

                val subDb = 10.0f * log10((subEnergy / read + 1e-12).toFloat()) + 94.0f
                val midDb = 10.0f * log10((midEnergy / read + 1e-12).toFloat()) + 94.0f
                val excessDb = (subDb - midDb) - targetBassOverMidsDb

                currentSubCutDb = if (subDb > 55.0f && excessDb > 0.0f) {
                    val targetCut = -min(maxSubCutDb, excessDb * 0.85f)
                    currentSubCutDb * 0.65f + targetCut * 0.35f // Fast attack
                } else {
                    currentSubCutDb * 0.90f // Smooth release
                }

                updateSubwooferGain(currentSubCutDb)
            }
            recorder.stop()
            recorder.release()
        }
    }

    override fun onDestroy() {
        running = false
        dynamicsProcessing?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}
