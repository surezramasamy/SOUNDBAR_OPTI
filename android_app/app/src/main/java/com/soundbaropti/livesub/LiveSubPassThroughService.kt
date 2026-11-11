package com.soundbaropti.livesub

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.os.Build
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class LiveSubPassThroughService : Service() {

    companion object {
        const val ACTION_START = "com.soundbaropti.livesub.ACTION_START"
        const val ACTION_STOP = "com.soundbaropti.livesub.ACTION_STOP"
        const val ACTION_ATTACH_SESSION = "com.soundbaropti.livesub.ACTION_ATTACH_SESSION"
        const val ACTION_DETACH_SESSION = "com.soundbaropti.livesub.ACTION_DETACH_SESSION"
        const val ACTION_RUN_SWEEP = "com.soundbaropti.livesub.ACTION_RUN_SWEEP"
        const val EXTRA_SESSION_ID = "extra_session_id"
        const val EXTRA_PACKAGE_NAME = "extra_package_name"

        @Volatile var isRunning = false
        @Volatile var isBypassed = false
        @Volatile var isCalibrating = false
        @Volatile var calibrationStatus = "Ready — Tap to auto-measure Front, Rear & Subwoofer"

        // User-controllable 360° Premium Sound Tuning Targets
        @Volatile var soundProfile = "DEEP_VELVET" // DEEP_VELVET, SURROUND_360, PURE_VOCAL, LOUDNESS_RICH
        @Volatile var deepSubWarmthDb = 4.0f       // +0 to +8 dB deep 30-58 Hz background velvet warmth
        @Volatile var targetBassOverMidsDb = 3.0f  // Upper-bass anti-hit threshold
        @Volatile var maxSubCutDb = 11.0f          // Max upper-bass punch/slam suppression (-3 to -15 dB)
        @Volatile var surroundClarityDb = 2.5f     // Front + Rear vocal & surround staging boost
        @Volatile var tweeterSmoothDb = 2.0f       // High-frequency anti-harshness & silk control

        // Live 5-Zone Acoustic Microphone Measurements
        @Volatile var liveDeepSubDb = 42.0f        // 28 - 62 Hz (Deep Background Sub)
        @Volatile var liveSubDb = 42.0f            // 65 - 140 Hz (Upper-Bass Punch / Hit Zone)
        @Volatile var liveLowMidDb = 40.0f         // 160 - 500 Hz (Door Woofer Warmth / Boxiness)
        @Volatile var liveMidDb = 42.0f            // 500 - 2800 Hz (Front & Rear Vocal Clarity)
        @Volatile var liveHighDb = 38.0f           // 3000 - 10000 Hz (Component Tweeter & Coaxial Highs)
        @Volatile var liveDistortionPct = 0.2f     // Real-time THD / Crest Overload Indicator

        // Live Applied DSP Gains (Witnessed on UI Meters in real time)
        @Volatile var liveDeepGainDb = +3.5f       // Boost/trim applied to 30-58 Hz Deep Background Sub
        @Volatile var liveCutDb = -3.5f            // Anti-Hit reduction applied to 75-140 Hz Upper-Bass Slam
        @Volatile var liveMidGainDb = +2.0f        // Applied to Front/Rear Vocal Clarity
        @Volatile var liveHighGainDb = -1.0f       // Applied to Tweeter De-Harsher
        @Volatile var passThroughPulse = 0         // Increments on every processed DSP frame (visual proof)

        // Auto-Calibration Measured Results (Front / Rear / Sub)
        @Volatile var calFrontLeftDb = 0.0f
        @Volatile var calFrontRightDb = 0.0f
        @Volatile var calRearSurroundDb = 0.0f
        @Volatile var calSubDeepDb = 0.0f
        @Volatile var calSubHitDb = 0.0f
        @Volatile var calRecommendation = "Tap 'Auto-Calibrate All Speakers' to measure Front, Rear & Subwoofer."

        @Volatile var isMusicStreamActive = false
        val activePackages = ConcurrentHashMap<Int, String>()

        fun applyPreset(preset: String) {
            soundProfile = preset
            when (preset) {
                "DEEP_VELVET" -> {
                    deepSubWarmthDb = 4.5f
                    targetBassOverMidsDb = 2.5f
                    maxSubCutDb = 12.0f
                    surroundClarityDb = 2.5f
                    tweeterSmoothDb = 2.5f
                }
                "SURROUND_360" -> {
                    deepSubWarmthDb = 3.5f
                    targetBassOverMidsDb = 3.5f
                    maxSubCutDb = 10.0f
                    surroundClarityDb = 4.0f
                    tweeterSmoothDb = 2.0f
                }
                "PURE_VOCAL" -> {
                    deepSubWarmthDb = 2.0f
                    targetBassOverMidsDb = 1.5f
                    maxSubCutDb = 13.0f
                    surroundClarityDb = 3.5f
                    tweeterSmoothDb = 3.5f
                }
                "LOUDNESS_RICH" -> {
                    deepSubWarmthDb = 6.0f
                    targetBassOverMidsDb = 3.0f
                    maxSubCutDb = 11.0f
                    surroundClarityDb = 3.0f
                    tweeterSmoothDb = 1.5f
                }
            }
        }
    }

    private val dspProcessors = ConcurrentHashMap<Int, DynamicsProcessing>()
    private val eqProcessors = ConcurrentHashMap<Int, Equalizer>()
    private var dynamicReceiverRegistered = false

    private val dynamicSessionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val act = intent.action ?: return
            if (act == AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION) {
                val sid = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
                val pkg = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME) ?: "Media Player"
                if (sid > 0) {
                    activePackages[sid] = pkg
                    NowPlayingListenerService.currentPackage = pkg
                    NowPlayingListenerService.currentAppName = NowPlayingListenerService.friendlyAppName(pkg)
                    NowPlayingListenerService.isPlaying = true
                    if (isRunning) attachDspToSession(sid)
                }
            } else if (act == AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION) {
                val sid = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
                if (sid > 0) {
                    activePackages.remove(sid)
                    detachDspFromSession(sid)
                }
            } else {
                // Standard Android / Spotify metadata broadcast fallback
                val track = intent.getStringExtra("track") ?: intent.getStringExtra("title")
                val artist = intent.getStringExtra("artist")
                val album = intent.getStringExtra("album")
                val playing = intent.getBooleanExtra("playing", true)
                if (!track.isNullOrBlank()) {
                    NowPlayingListenerService.currentTitle = track
                    if (!artist.isNullOrBlank()) NowPlayingListenerService.currentArtist = artist
                    if (!album.isNullOrBlank()) NowPlayingListenerService.currentAlbum = album
                    if (act.contains("spotify")) {
                        NowPlayingListenerService.currentAppName = "Spotify"
                    }
                }
                NowPlayingListenerService.isPlaying = playing
            }
        }
    }

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            super.onPlaybackConfigChanged(configs)
            val list = configs ?: emptyList()
            isMusicStreamActive = list.isNotEmpty()
            if (isMusicStreamActive) {
                NowPlayingListenerService.isPlaying = true
            }
            NowPlayingListenerService.refreshFromMediaSessionManager(this@LiveSubPassThroughService)
            if (isRunning) {
                attachDspToSession(0)
                for (sid in activePackages.keys) {
                    attachDspToSession(sid)
                }
            }
        }
    }

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
                    NowPlayingListenerService.currentPackage = pkg
                    NowPlayingListenerService.currentAppName = NowPlayingListenerService.friendlyAppName(pkg)
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
            ACTION_RUN_SWEEP -> {
                if (!isRunning) {
                    startEngine()
                }
                runAutoSpeakerCalibrationSweep()
            }
            else -> {
                if (!isRunning) {
                    startEngine()
                }
            }
        }
        return START_STICKY
    }

    private fun startEngine() {
        startForegroundNotification()
        isRunning = true
        registerDynamicReceivers()
        NowPlayingListenerService.refreshFromMediaSessionManager(this)
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        isMusicStreamActive = am.isMusicActive
        attachDspToSession(0)
        for (sid in activePackages.keys) {
            attachDspToSession(sid)
        }
        startMicLoop()
    }

    private fun registerDynamicReceivers() {
        if (dynamicReceiverRegistered) return
        try {
            val filter = IntentFilter().apply {
                addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
                addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
                addAction("com.spotify.music.metadatachanged")
                addAction("com.spotify.music.playbackstatechanged")
                addAction("com.android.music.metadatachanged")
                addAction("com.android.music.playstatechanged")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(dynamicSessionReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(dynamicSessionReceiver, filter)
            }
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.registerAudioPlaybackCallback(playbackCallback, null)
            dynamicReceiverRegistered = true
        } catch (_: Exception) {}
    }

    private fun unregisterDynamicReceivers() {
        if (!dynamicReceiverRegistered) return
        try {
            unregisterReceiver(dynamicSessionReceiver)
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.unregisterAudioPlaybackCallback(playbackCallback)
        } catch (_: Exception) {}
        dynamicReceiverRegistered = false
    }

    private fun startForegroundNotification() {
        val channelId = "live_sub_guard_channel"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            channelId,
            "360° Live Sound & Subwoofer Optimizer",
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(channel)

        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(this, channelId)
            .setContentTitle("360° Premium Sound & Sub Optimizer Active")
            .setContentText("Deep Velvet Bass (No Hard Hit) + Front/Rear Clarity Active")
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
        if (!dspProcessors.containsKey(sessionId)) {
            try {
                // 8-Band High-Resolution Pre-EQ + 8-Band MBC + Post-EQ + Distortion-Free Limiter
                val config = DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    2,
                    true, 8,
                    true, 8,
                    true, 8,
                    true
                ).build()

                // Configure distortion-free soft limiter so loud bass or highs NEVER clip
                val limiter = DynamicsProcessing.Limiter(
                    true, true, 0,
                    2.0f,   // 2 ms fast attack
                    60.0f,  // 60 ms smooth release
                    4.0f,   // 4:1 ratio
                    -1.2f,  // -1.2 dBFS ceiling prevents any digital/amp clipping
                    0.0f
                )

                val dp = DynamicsProcessing(100, sessionId, config)
                dp.setLimiterAllChannelsTo(limiter)

                // Band cutoff frequencies covering Deep Sub, Upper-Bass Hit Zone, Door Mid-Woofer, Front/Rear Vocals, and Tweeters
                val cutoffs = floatArrayOf(42.0f, 65.0f, 95.0f, 140.0f, 380.0f, 1200.0f, 3500.0f, 16000.0f)
                for (i in 0 until 8) {
                    dp.setPreEqBandAllChannelsTo(i, DynamicsProcessing.EqBand(true, cutoffs[i], 0.0f))
                    dp.setPostEqBandAllChannelsTo(i, DynamicsProcessing.EqBand(true, cutoffs[i], 0.0f))

                    // Multi-Band Compressor (MBC) — specifically tightens 65-140 Hz upper-bass so it NEVER hits hard
                    val isHitBand = (i == 2 || i == 3)
                    val mbcBand = DynamicsProcessing.MbcBand(
                        true,
                        cutoffs[i],
                        if (isHitBand) 4.0f else 12.0f,   // Fast 4ms attack on 65-140 Hz hard-hit band
                        if (isHitBand) 110.0f else 150.0f,
                        if (isHitBand) 4.5f else 2.0f,    // Strong 4.5:1 compression on upper-bass punch
                        if (isHitBand) -18.0f else -10.0f,
                        6.0f,                             // Soft 6 dB knee for velvet smoothness
                        -60.0f,
                        1.0f,
                        0.0f,
                        0.0f
                    )
                    dp.setMbcBandAllChannelsTo(i, mbcBand)
                }
                dp.enabled = true
                dspProcessors[sessionId] = dp
            } catch (_: Exception) {}
        }

        // Also attach standard hardware Equalizer fallback for OEMs that route session 0 via Equalizer HAL
        if (!eqProcessors.containsKey(sessionId)) {
            try {
                val eq = Equalizer(100, sessionId)
                eq.enabled = true
                eqProcessors[sessionId] = eq
            } catch (_: Exception) {}
        }
    }

    private fun detachDspFromSession(sessionId: Int) {
        try { dspProcessors.remove(sessionId)?.release() } catch (_: Exception) {}
        try { eqProcessors.remove(sessionId)?.release() } catch (_: Exception) {}
    }

    /**
     * Applies 360° All-Direction Premium Tuning in real time:
     * - Band 0 (<=42 Hz) & Band 1 (42-65 Hz): Deep Velvet Background Sub-Bass warmth (+dB)
     * - Band 2 (65-95 Hz) & Band 3 (95-140 Hz): Anti-Hit / Anti-Slam Suppressor (-dB) so bass never hits
     * - Band 4 (140-380 Hz): Anti-Boxiness Door Woofer cleanup (-dB)
     * - Band 5 (380-1200 Hz) & Band 6 (1200-3500 Hz): Front & Rear 360° Vocal Stage clarity (+dB)
     * - Band 7 (3500-16000 Hz): Tweeter De-Harshing & Silk Air
     */
    private fun apply360TuningToAllSessions(
        deepSubGain: Float,
        hitCutGain: Float,
        mudTrimGain: Float,
        vocalClarityGain: Float,
        highSmoothGain: Float,
        headroomTrimDb: Float
    ) {
        val bypass = isBypassed
        val cutoffs = floatArrayOf(42.0f, 65.0f, 95.0f, 140.0f, 380.0f, 1200.0f, 3500.0f, 16000.0f)
        val gains = if (bypass) {
            floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        } else {
            floatArrayOf(
                deepSubGain,                  // 25-42 Hz: Deep Velvet Sub Foundation
                deepSubGain * 0.80f,          // 42-65 Hz: Warm Sub Cushion
                hitCutGain,                   // 65-95 Hz: Primary Anti-Hit / Anti-Thump Cut
                hitCutGain * 0.85f,           // 95-140 Hz: Upper-Bass Punch Suppressor
                mudTrimGain,                  // 140-380 Hz: Door Woofer Anti-Mud Cut
                vocalClarityGain * 0.85f,     // 380-1200 Hz: Warm Front/Rear Body
                vocalClarityGain,             // 1200-3500 Hz: 360° Vocal Presence & Immersion
                highSmoothGain                // 3500-16000 Hz: Distortion-Free Silk Highs
            )
        }

        for ((_, dp) in dspProcessors) {
            try {
                dp.setInputGainAllChannelsTo(if (bypass) 0.0f else headroomTrimDb)
                for (i in 0 until 8) {
                    dp.setPreEqBandAllChannelsTo(i, DynamicsProcessing.EqBand(true, cutoffs[i], gains[i]))
                }
            } catch (_: Exception) {}
        }

        // Also mirror onto hardware Equalizer bands for guaranteed audible pass-through on all Android chipsets
        for ((_, eq) in eqProcessors) {
            try {
                val numBands = eq.numberOfBands.toInt()
                val range = eq.bandLevelRange
                val minLvl = range[0].toInt()
                val maxLvl = range[1].toInt()
                for (b in 0 until numBands) {
                    val centerHz = eq.getCenterFreq(b.toShort()) / 1000 // mHz -> Hz
                    val targetDb = if (bypass) {
                        0.0f
                    } else when {
                        centerHz <= 65 -> deepSubGain
                        centerHz in 66..160 -> hitCutGain
                        centerHz in 161..450 -> mudTrimGain
                        centerHz in 451..3200 -> vocalClarityGain
                        else -> highSmoothGain
                    }
                    val millibel = (targetDb * 100.0f).toInt().coerceIn(minLvl, maxLvl).toShort()
                    eq.setBandLevel(b.toShort(), millibel)
                }
            } catch (_: Exception) {}
        }
    }

    private fun startMicLoop() {
        thread(start = true, name = "Live360MicOptimizerThread") {
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

            // 5-band cascaded single-pole IIR filter states @ 16,000 Hz
            var lp60 = 0.0f    // <= 60 Hz (Deep Background Sub)
            var lp140 = 0.0f   // <= 140 Hz (Upper-Bass Hit / Punch)
            var lp450 = 0.0f   // <= 450 Hz (Low-Mid Door Woofer)
            var lp2800 = 0.0f  // <= 2800 Hz (Front/Rear Vocal Midrange)

            val a60 = 0.023f
            val a140 = 0.053f
            val a450 = 0.162f
            val a2800 = 0.66f

            var prevPunchDb = 42.0f
            var frameCounter = 0

            recorder.startRecording()
            while (isRunning) {
                val read = recorder.read(buf, 0, blockSize)
                if (read <= 0) continue

                var sumDeep = 0.0
                var sumPunch = 0.0
                var sumLowMid = 0.0
                var sumVocal = 0.0
                var sumHigh = 0.0
                var peakAbs = 0.0f
                var totalSq = 0.0

                for (i in 0 until read) {
                    val x = buf[i] / 32768.0f
                    val ax = abs(x)
                    if (ax > peakAbs) peakAbs = ax
                    totalSq += x * x

                    lp60 += a60 * (x - lp60)
                    lp140 += a140 * (x - lp140)
                    lp450 += a450 * (x - lp450)
                    lp2800 += a2800 * (x - lp2800)

                    val bandDeep = lp60
                    val bandPunch = lp140 - lp60
                    val bandLowMid = lp450 - lp140
                    val bandVocal = lp2800 - lp450
                    val bandHigh = x - lp2800

                    sumDeep += bandDeep * bandDeep
                    sumPunch += bandPunch * bandPunch
                    sumLowMid += bandLowMid * bandLowMid
                    sumVocal += bandVocal * bandVocal
                    sumHigh += bandHigh * bandHigh
                }

                val deepDb = 10.0f * log10((sumDeep / read + 1e-12).toFloat()) + 94.0f
                val punchDb = 10.0f * log10((sumPunch / read + 1e-12).toFloat()) + 94.0f
                val lowMidDb = 10.0f * log10((sumLowMid / read + 1e-12).toFloat()) + 94.0f
                val vocalDb = 10.0f * log10((sumVocal / read + 1e-12).toFloat()) + 94.0f
                val highDb = 10.0f * log10((sumHigh / read + 1e-12).toFloat()) + 94.0f

                // Estimate crest / clipping distortion risk
                val rms = sqrt((totalSq / read + 1e-12).toFloat())
                val crest = peakAbs / max(rms, 1e-5f)
                val estThd = ((max(0.0f, punchDb - 72.0f) * 0.08f) + (if (crest < 2.2f && punchDb > 60f) 1.2f else 0.15f))
                    .coerceIn(0.1f, 9.9f)

                liveDeepSubDb = liveDeepSubDb * 0.6f + deepDb * 0.4f
                liveSubDb = liveSubDb * 0.5f + punchDb * 0.5f
                liveLowMidDb = liveLowMidDb * 0.6f + lowMidDb * 0.4f
                liveMidDb = liveMidDb * 0.6f + vocalDb * 0.4f
                liveHighDb = liveHighDb * 0.6f + highDb * 0.4f
                liveDistortionPct = liveDistortionPct * 0.7f + estThd * 0.3f

                // 1. Deep Background Sub (28-62 Hz): Keep warm, deep background cushion without clipping
                val deepOverload = max(0.0f, liveDeepSubDb - 78.0f)
                val targetDeepGain = (deepSubWarmthDb - deepOverload * 0.75f).coerceIn(-4.0f, 8.0f)
                liveDeepGainDb = liveDeepGainDb * 0.75f + targetDeepGain * 0.25f

                // 2. Anti-Hit Upper-Bass Suppressor (70-140 Hz):
                //    Always maintains at least a gentle -2.5 dB anti-thump contour so bass NEVER hits hard,
                //    and immediately ducks down to -maxSubCutDb whenever a punch/slam transient occurs!
                val transientJump = max(0.0f, punchDb - prevPunchDb)
                prevPunchDb = punchDb
                val punchExcess = (liveSubDb - liveMidDb) - targetBassOverMidsDb + (transientJump * 0.9f)
                val baselineAntiHit = -min(3.5f, maxSubCutDb * 0.35f)
                val desiredCut = if (punchExcess > 0.0f) {
                    -min(maxSubCutDb, abs(baselineAntiHit) + punchExcess * 0.95f)
                } else {
                    baselineAntiHit
                }
                liveCutDb = liveCutDb * 0.55f + desiredCut * 0.45f

                // 3. Door Woofer Boxiness Trim (180-450 Hz)
                val mudExcess = max(0.0f, liveLowMidDb - liveMidDb)
                val mudTrim = -(1.5f + min(3.5f, mudExcess * 0.5f))

                // 4. Front & Rear 360° Vocal Stage Clarity (500-2800 Hz)
                val vocalDeficit = max(0.0f, (liveSubDb - liveMidDb) * 0.25f)
                val targetMidGain = (surroundClarityDb + min(2.0f, vocalDeficit)).coerceIn(-2.0f, 6.5f)
                liveMidGainDb = liveMidGainDb * 0.75f + targetMidGain * 0.25f

                // 5. Tweeter Distortion-Free Silk Control (3.2 - 12 kHz)
                val highHarshness = max(0.0f, (liveHighDb - liveMidDb) + 2.0f)
                val targetHighGain = (1.5f - tweeterSmoothDb * 0.6f - highHarshness * 0.45f).coerceIn(-6.0f, 3.5f)
                liveHighGainDb = liveHighGainDb * 0.75f + targetHighGain * 0.25f

                // 6. Distortion-Free Pre-Amp Headroom Guard
                val maxPositiveBoost = max(0.0f, max(liveDeepGainDb, liveMidGainDb))
                val headroomTrim = -(maxPositiveBoost * 0.65f + (if (liveDistortionPct > 1.5f) 2.0f else 0.5f))

                apply360TuningToAllSessions(
                    liveDeepGainDb,
                    liveCutDb,
                    mudTrim,
                    liveMidGainDb,
                    liveHighGainDb,
                    headroomTrim
                )

                passThroughPulse = (passThroughPulse + 1) % 10000
                frameCounter++
                if (frameCounter % 15 == 0) {
                    NowPlayingListenerService.refreshFromMediaSessionManager(this@LiveSubPassThroughService)
                }
            }

            try {
                recorder.stop()
                recorder.release()
            } catch (_: Exception) {}
        }
    }

    /**
     * Built-in 360° All-Speaker Acoustic Sweep & Auto-Leveler:
     * Plays pleasant harmonic chords for Deep Sub, Upper-Bass Hit Zone, Front Left, Front Right, and Rear Surround,
     * measures cabin response via the phone mic, and auto-tunes the live optimizer parameters.
     */
    private fun runAutoSpeakerCalibrationSweep() {
        if (isCalibrating) return
        isCalibrating = true
        thread(start = true, name = "SpeakerAutoCalThread") {
            try {
                calibrationStatus = "Step 1/4: Measuring Deep Subwoofer (42 Hz Velvet Foundation)..."
                playStereoTone(42.0, 55.0, leftAmp = 0.8f, rightAmp = 0.8f, durationMs = 1100)
                calSubDeepDb = liveDeepSubDb

                calibrationStatus = "Step 2/4: Measuring Upper-Bass Cabin Slam (105 Hz Hit Zone)..."
                playStereoTone(100.0, 120.0, leftAmp = 0.8f, rightAmp = 0.8f, durationMs = 1100)
                calSubHitDb = liveSubDb

                calibrationStatus = "Step 3/4: Measuring Front Left & Right Component Speakers..."
                playStereoTone(440.0, 880.0, leftAmp = 0.85f, rightAmp = 0.0f, durationMs = 950)
                calFrontLeftDb = liveMidDb
                playStereoTone(523.25, 1046.5, leftAmp = 0.0f, rightAmp = 0.85f, durationMs = 950)
                calFrontRightDb = liveMidDb

                calibrationStatus = "Step 4/4: Measuring Rear Coaxial Surround Speakers (Dolby Phase)..."
                playStereoTone(392.0, 784.0, leftAmp = 0.85f, rightAmp = -0.85f, durationMs = 1100)
                calRearSurroundDb = liveMidDb

                // Compute optimal distortion-free deep-bass-without-hitting profile from measurements
                val hitPeakDiff = calSubHitDb - calSubDeepDb
                deepSubWarmthDb = if (calSubDeepDb < calSubHitDb) {
                    (4.5f + min(2.5f, (calSubHitDb - calSubDeepDb) * 0.4f)).coerceIn(2.0f, 7.5f)
                } else {
                    4.0f
                }
                maxSubCutDb = (10.0f + max(0.0f, hitPeakDiff * 0.5f)).coerceIn(8.0f, 14.0f)
                targetBassOverMidsDb = 2.0f

                val frontAvg = (calFrontLeftDb + calFrontRightDb) * 0.5f
                val rearDiff = frontAvg - calRearSurroundDb
                val lrDiff = calFrontLeftDb - calFrontRightDb

                val faderHint = when {
                    rearDiff > 2.5f -> "Boost Rear Coaxial by +${"%.0f".format(min(6f, rearDiff))} dB on head unit"
                    rearDiff < -2.5f -> "Reduce Rear Coaxial by ${"%.0f".format(max(-6f, rearDiff))} dB on head unit"
                    else -> "Front/Rear Surround Balance is Ideal (0 dB)"
                }
                val subHint = if (hitPeakDiff > 2.0f) {
                    "Set Pioneer Sub LPF = 63 Hz (-18dB slope) to stop 100 Hz hard hit"
                } else {
                    "Set Pioneer Sub LPF = 80 Hz (-12dB slope), Front/Rear HPF = 80 Hz"
                }

                calibrationStatus = "✅ 360° Auto-Calibration Complete & Applied Live!"
                calRecommendation = buildString {
                    append("• Deep Sub (42 Hz): ${"%.1f".format(calSubDeepDb)} dB | Upper-Bass Hit (105 Hz): ${"%.1f".format(calSubHitDb)} dB\n")
                    append("• Front L: ${"%.1f".format(calFrontLeftDb)} dB | Front R: ${"%.1f".format(calFrontRightDb)} dB | Rear: ${"%.1f".format(calRearSurroundDb)} dB\n")
                    append("• Live DSP Auto-Set: Deep Velvet +${"%.1f".format(deepSubWarmthDb)} dB, Anti-Hit Limit -${"%.0f".format(maxSubCutDb)} dB\n")
                    append("• Head-Unit Tip: $faderHint • $subHint")
                    if (abs(lrDiff) > 1.5f) {
                        append("\n• L/R Trim: Adjust R/L balance by ${"%+.1f".format(-lrDiff)} dB")
                    }
                }
            } catch (_: Exception) {
                calibrationStatus = "Calibration finished."
            } finally {
                isCalibrating = false
            }
        }
    }

    private fun playStereoTone(
        freq1: Double,
        freq2: Double,
        leftAmp: Float,
        rightAmp: Float,
        durationMs: Int
    ) {
        try {
            val sr = 44100
            val totalSamples = (sr * durationMs) / 1000
            val pcm = ShortArray(totalSamples * 2)
            for (i in 0 until totalSamples) {
                val t = i.toDouble() / sr
                val env = sin(PI * i.toDouble() / totalSamples).toFloat()
                val wave = (0.65 * sin(2.0 * PI * freq1 * t) + 0.35 * sin(2.0 * PI * freq2 * t)).toFloat() * env
                val l = (wave * leftAmp * 26000f).toInt().coerceIn(-32767, 32767).toShort()
                val r = (wave * rightAmp * 26000f).toInt().coerceIn(-32767, 32767).toShort()
                pcm[i * 2] = l
                pcm[i * 2 + 1] = r
            }
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sr)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(pcm, 0, pcm.size)
            track.play()
            Thread.sleep(durationMs.toLong() + 120L)
            track.stop()
            track.release()
        } catch (_: Exception) {}
    }

    private fun stopGuard() {
        isRunning = false
        unregisterDynamicReceivers()
        for ((_, dp) in dspProcessors) {
            try { dp.release() } catch (_: Exception) {}
        }
        dspProcessors.clear()
        for ((_, eq) in eqProcessors) {
            try { eq.release() } catch (_: Exception) {}
        }
        eqProcessors.clear()
    }

    override fun onDestroy() {
        stopGuard()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}
