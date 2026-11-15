package com.soundbaropti.livesub

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var statusBadge: TextView
    private lateinit var passThroughBadge: TextView
    private lateinit var songTitleText: TextView
    private lateinit var songArtistText: TextView
    private lateinit var playPauseBtn: Button
    private lateinit var enableNotifBtn: Button
    private lateinit var bypassBtn: Button
    private lateinit var toggleBtn: Button

    // Preset buttons
    private lateinit var presetVelvetBtn: Button
    private lateinit var presetSurroundBtn: Button
    private lateinit var presetVocalBtn: Button
    private lateinit var presetLoudnessBtn: Button

    // 5-Zone Live Meters
    private lateinit var deepSubValText: TextView
    private lateinit var deepSubProgress: ProgressBar
    private lateinit var hitBassValText: TextView
    private lateinit var hitBassProgress: ProgressBar
    private lateinit var cutValText: TextView
    private lateinit var cutProgress: ProgressBar
    private lateinit var midValText: TextView
    private lateinit var midProgress: ProgressBar
    private lateinit var highValText: TextView
    private lateinit var thdBadgeText: TextView

    // Auto-Calibration UI
    private lateinit var autoCalBtn: Button
    private lateinit var calProgressBar: ProgressBar
    private lateinit var calStatusText: TextView
    private lateinit var calReportText: TextView

    // Poweramp-Style 10-Band EQ, Tone & Limiter UI
    private lateinit var optimizeSpotifyBtn: Button
    private lateinit var spotifyOptStatusText: TextView
    private lateinit var autoEqToggleBtn: Button
    private lateinit var limiterToggleBtn: Button
    private lateinit var toneBassLabel: TextView
    private lateinit var toneBassSeek: SeekBar
    private lateinit var toneTrebleLabel: TextView
    private lateinit var toneTrebleSeek: SeekBar
    private lateinit var preampLabel: TextView
    private lateinit var preampSeek: SeekBar
    private lateinit var limiterCeilingLabel: TextView
    private lateinit var limiterCeilingSeek: SeekBar
    private val eqBandLabels = mutableListOf<TextView>()
    private val eqBandSeeks = mutableListOf<SeekBar>()

    // Poweramp Top Tab Bar & AudioFX UI (v3.0 Eclipsa + Fire TV Max)
    private lateinit var tabEclipsaBtn: Button
    private lateinit var tabEqBtn: Button
    private lateinit var tabFxBtn: Button
    private lateinit var tabMicBtn: Button
    private lateinit var eclipsaTabContainer: LinearLayout
    private lateinit var eqTabContainer: LinearLayout
    private lateinit var fxTabContainer: LinearLayout
    private lateinit var micTabContainer: LinearLayout

    // Eclipsa Audio & Fire TV Stick Max UI
    private lateinit var eclipsaBridgeBadge: TextView
    private lateinit var eclipsaActivateBtn: Button
    private lateinit var eclipsaAuditBtn: Button
    private lateinit var eclipsaAuditVerdictText: TextView
    private lateinit var eclipsaHdmiReportText: TextView
    private lateinit var eclipsaCodecReportText: TextView
    private lateinit var eclipsaChannelMapText: TextView
    private lateinit var eclipsaTestStatusText: TextView
    private lateinit var eclipsaWebView: WebView
    private var activeEclipsaVideoId = "BnbxkFx7uzc"

    private lateinit var fxToggleBtn: Button
    private lateinit var stereoExpandLabel: TextView
    private lateinit var stereoExpandSeek: SeekBar
    private lateinit var bassBoostLabel: TextView
    private lateinit var bassBoostSeek: SeekBar
    private val reverbPresetBtns = mutableListOf<Button>()
    private var activeTabIndex = 0

    // Sliders
    private lateinit var deepWarmthLabel: TextView
    private lateinit var threshValueLabel: TextView
    private lateinit var maxCutValueLabel: TextView
    private lateinit var surroundLabel: TextView
    private lateinit var tweeterLabel: TextView

    private val uiHandler = Handler(Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            refreshMeters()
            uiHandler.postDelayed(this, 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        requestNeededPermissions()
    }

    override fun onResume() {
        super.onResume()
        NowPlayingListenerService.refreshFromMediaSessionManager(this)
        uiHandler.post(updateRunnable)
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(updateRunnable)
    }

    override fun onDestroy() {
        try { eclipsaWebView.destroy() } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun startServiceCompat(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun requestNeededPermissions() {
        val perms = mutableListOf<String>()
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)) {
            perms.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 200)
        }
    }

    private fun toggleService() {
        val intent = Intent(this, LiveSubPassThroughService::class.java)
        if (LiveSubPassThroughService.isRunning) {
            intent.action = LiveSubPassThroughService.ACTION_STOP
            startService(intent)
        } else {
            intent.action = LiveSubPassThroughService.ACTION_START
            startServiceCompat(intent)
        }
    }

    private fun activateEclipsaBridge() {
        LiveSubPassThroughService.applyPreset("ECLIPSA_714")
        val intent = Intent(this, LiveSubPassThroughService::class.java).apply {
            action = LiveSubPassThroughService.ACTION_START
        }
        startServiceCompat(intent)
        refreshMeters()
    }

    private fun triggerEclipsaAudit() {
        val intent = Intent(this, LiveSubPassThroughService::class.java).apply {
            action = LiveSubPassThroughService.ACTION_RUN_ECLIPSA_AUDIT
        }
        startServiceCompat(intent)
    }

    private fun triggerEclipsaTest(testId: String) {
        LiveSubPassThroughService.applyPreset("ECLIPSA_714")
        val intent = Intent(this, LiveSubPassThroughService::class.java).apply {
            action = LiveSubPassThroughService.ACTION_PLAY_ECLIPSA_TEST
            putExtra(LiveSubPassThroughService.EXTRA_ECLIPSA_TEST_ID, testId)
        }
        startServiceCompat(intent)
    }

    private fun playVideoInBuiltInEclipsaPlayer(videoId: String) {
        activeEclipsaVideoId = videoId
        activateEclipsaBridge()
        val embedHtml = """
            <!DOCTYPE html>
            <html><body style="margin:0;background:#000;">
            <iframe width="100%" height="100%" style="position:fixed;top:0;left:0;width:100%;height:100%;border:0;"
              src="https://www.youtube.com/embed/$videoId?autoplay=1&rel=0&playsinline=1"
              allow="autoplay; encrypted-media; picture-in-picture" allowfullscreen></iframe>
            </body></html>
        """.trimIndent()
        eclipsaWebView.loadDataWithBaseURL("https://www.youtube.com", embedHtml, "text/html", "UTF-8", null)
    }

    private fun openVideoInFireTvYouTube(videoId: String) {
        activeEclipsaVideoId = videoId
        activateEclipsaBridge()
        try {
            val ytIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$videoId"))
            startActivity(ytIntent)
        } catch (_: Exception) {}
    }

    private fun triggerAutoCalibration() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestNeededPermissions()
            return
        }
        val intent = Intent(this, LiveSubPassThroughService::class.java).apply {
            action = LiveSubPassThroughService.ACTION_RUN_SWEEP
        }
        startServiceCompat(intent)
    }

    private fun triggerSpotifyOptimize() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestNeededPermissions()
            return
        }
        val intent = Intent(this, LiveSubPassThroughService::class.java).apply {
            action = LiveSubPassThroughService.ACTION_OPTIMIZE_MUSIC
        }
        startServiceCompat(intent)
    }

    private fun openNotificationAccessSettings() {
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun openAndroidAutoSettings() {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setPackage("com.google.android.projection.gearhead")
            }
            startActivity(intent)
        } catch (_: Exception) {
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:com.google.android.projection.gearhead")
                }
                startActivity(intent)
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
    }

    private fun refreshMeters() {
        val running = LiveSubPassThroughService.isRunning
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val musicActive = am.isMusicActive || NowPlayingListenerService.isPlaying

        statusBadge.text = if (running) {
            "● 360° PREMIUM SOUND & SUB GUARD ACTIVE (BACKGROUND + ANDROID AUTO)"
        } else {
            "○ ENGINE STOPPED — TAP START BELOW"
        }
        statusBadge.setTextColor(if (running) Color.parseColor("#3FB950") else Color.parseColor("#8B949E"))

        // Now Playing & Pass-Through Proof Card
        val notifGranted = NowPlayingListenerService.isNotificationAccessGranted(this)
        enableNotifBtn.visibility = if (notifGranted) android.view.View.GONE else android.view.View.VISIBLE

        val appName = NowPlayingListenerService.currentAppName
        val pulse = LiveSubPassThroughService.passThroughPulse
        passThroughBadge.text = when {
            !running -> "⚪ PASS-THROUGH STANDBY • Start Optimizer to Intercept Audio"
            LiveSubPassThroughService.isBypassed -> "🟠 A/B BYPASS MODE ACTIVE • Raw Unprocessed Audio ($appName)"
            musicActive -> "🟢 PASS-THROUGH LOCKED & STREAMING • $appName (DSP Frame #$pulse)"
            else -> "🟢 PASS-THROUGH READY • Waiting for Play in $appName (Frame #$pulse)"
        }
        passThroughBadge.setTextColor(
            when {
                !running -> Color.parseColor("#8B949E")
                LiveSubPassThroughService.isBypassed -> Color.parseColor("#D29922")
                else -> Color.parseColor("#3FB950")
            }
        )

        songTitleText.text = if (NowPlayingListenerService.currentTitle == "Waiting for Music Player..." && musicActive) {
            "🎵 Active Music Stream Detected ($appName)"
        } else {
            "🎵 ${NowPlayingListenerService.currentTitle}"
        }

        val albumPart = if (NowPlayingListenerService.currentAlbum.isNotBlank()) " • ${NowPlayingListenerService.currentAlbum}" else ""
        songArtistText.text = if (!notifGranted && NowPlayingListenerService.currentTitle == "Waiting for Music Player...") {
            "Tap 'Enable Song Title Sync' below to display exact Spotify / YouTube Music track info"
        } else {
            "${NowPlayingListenerService.currentArtist}$albumPart ($appName)"
        }

        playPauseBtn.text = if (musicActive) "⏸ Pause" else "▶ Play"

        // Bypass button state
        val bypassed = LiveSubPassThroughService.isBypassed
        bypassBtn.text = if (bypassed) {
            "🔊 A/B MODE: RAW BYPASS (Tap to Re-Enable 360° Deep Velvet DSP)"
        } else {
            "🔊 A/B TEST: 360° PREMIUM DSP ON (Tap to Compare Raw Unprocessed)"
        }
        bypassBtn.background = roundedBg(if (bypassed) "#D29922" else "#21262D", "#58A6FF")
        bypassBtn.setTextColor(if (bypassed) Color.BLACK else Color.parseColor("#58A6FF"))

        // Start/Stop button
        toggleBtn.text = if (running) "⏹ STOP 360° LIVE SOUND OPTIMIZER" else "▶ START 360° LIVE SOUND & SUB OPTIMIZER"
        toggleBtn.background = roundedBg(if (running) "#F85149" else "#3FB950")
        toggleBtn.setTextColor(if (running) Color.WHITE else Color.BLACK)

        // Highlight active preset button
        val prof = LiveSubPassThroughService.soundProfile
        stylePresetBtn(presetVelvetBtn, prof == "DEEP_VELVET")
        stylePresetBtn(presetSurroundBtn, prof == "SURROUND_360")
        stylePresetBtn(presetVocalBtn, prof == "PURE_VOCAL")
        stylePresetBtn(presetLoudnessBtn, prof == "LOUDNESS_RICH")

        // 5-Zone Live Meters
        val deepDb = LiveSubPassThroughService.liveDeepSubDb
        val deepGain = LiveSubPassThroughService.liveDeepGainDb
        val hitDb = LiveSubPassThroughService.liveSubDb
        val cutDb = LiveSubPassThroughService.liveCutDb
        val midDb = LiveSubPassThroughService.liveMidDb
        val midGain = LiveSubPassThroughService.liveMidGainDb
        val highDb = LiveSubPassThroughService.liveHighDb
        val highGain = LiveSubPassThroughService.liveHighGainDb
        val thd = LiveSubPassThroughService.liveDistortionPct

        deepSubValText.text = "%.1f dB  (Velvet Boost: %+.1f dB)".format(deepDb, deepGain)
        deepSubProgress.progress = ((deepDb - 35f) / 55f * 100f).toInt().coerceIn(0, 100)

        hitBassValText.text = "%.1f dB".format(hitDb)
        hitBassProgress.progress = ((hitDb - 35f) / 55f * 100f).toInt().coerceIn(0, 100)

        cutValText.text = "%.1f dB Anti-Hit Cut".format(cutDb)
        cutValText.setTextColor(if (cutDb < -4.0f) Color.parseColor("#FF7B00") else Color.parseColor("#3FB950"))
        cutProgress.progress = (kotlin.math.abs(cutDb) / LiveSubPassThroughService.maxSubCutDb * 100f).toInt().coerceIn(0, 100)

        midValText.text = "%.1f dB  (360° Stage: %+.1f dB)".format(midDb, midGain)
        midProgress.progress = ((midDb - 35f) / 55f * 100f).toInt().coerceIn(0, 100)

        highValText.text = "Tweeter Highs: %.1f dB (Silk Trim: %+.1f dB)".format(highDb, highGain)
        thdBadgeText.text = "Distortion Guard: %.1f%% THD • 100%% CLIP-FREE HEADROOM".format(thd)
        thdBadgeText.setTextColor(if (thd > 1.8f) Color.parseColor("#D29922") else Color.parseColor("#3FB950"))

        // Auto-Calibration Status
        calStatusText.text = LiveSubPassThroughService.calibrationStatus
        calProgressBar.progress = LiveSubPassThroughService.calibrationProgress
        calReportText.text = LiveSubPassThroughService.calRecommendation
        autoCalBtn.isEnabled = !LiveSubPassThroughService.isCalibrating
        autoCalBtn.text = if (LiveSubPassThroughService.isCalibrating) {
            "⏳ MEASURING FRONT, REAR & SUB SPEAKERS..."
        } else {
            "🎯 AUTO-CALIBRATE FRONT + REAR + SUBWOOFER (4-SEC SWEEP)"
        }

        // Poweramp-Style Spotify Auto-Optimizer & 10-Band EQ Live Refresh
        spotifyOptStatusText.text = LiveSubPassThroughService.musicOptStatus
        optimizeSpotifyBtn.isEnabled = !LiveSubPassThroughService.isOptimizingMusic
        optimizeSpotifyBtn.text = if (LiveSubPassThroughService.isOptimizingMusic) {
            "⏳ MEASURING PLAYING SPOTIFY AUDIO..."
        } else {
            "🎧 MEASURE & OPTIMIZE PLAYING SPOTIFY SONG (AUTO-EQ)"
        }

        val autoOn = LiveSubPassThroughService.autoPowerampOptimize
        autoEqToggleBtn.text = if (autoOn) "🤖 LIVE AUTO-EQ: ON (Mic + Manual)" else "🎛️ LIVE AUTO-EQ: OFF (Manual Only)"
        autoEqToggleBtn.background = roundedBg(if (autoOn) "#3FB950" else "#21262D", "#3FB950")
        autoEqToggleBtn.setTextColor(if (autoOn) Color.BLACK else Color.WHITE)

        val limOn = LiveSubPassThroughService.limiterEnabled
        limiterToggleBtn.text = if (limOn) "🛡️ LIMITER: ON (Clip-Free)" else "⚠️ LIMITER: OFF"
        limiterToggleBtn.background = roundedBg(if (limOn) "#58A6FF" else "#21262D", "#58A6FF")
        limiterToggleBtn.setTextColor(if (limOn) Color.BLACK else Color.WHITE)

        toneBassLabel.text = "Bass Tone (31–62 Hz Deep Sub): %+.1f dB".format(LiveSubPassThroughService.toneBassDb)
        toneTrebleLabel.text = "Treble Tone (8–16 kHz Silk Air): %+.1f dB".format(LiveSubPassThroughService.toneTrebleDb)
        preampLabel.text = "Pre-Amp Headroom (Anti-Clip): %.1f dB".format(LiveSubPassThroughService.preampDb)
        limiterCeilingLabel.text = "Limiter Ceiling: %.1f dBFS".format(LiveSubPassThroughService.limiterCeilingDb)

        for (i in 0 until eqBandLabels.size) {
            val labelName = LiveSubPassThroughService.EQ_LABELS[i]
            val userVal = LiveSubPassThroughService.userEqOffsetsDb[i]
            val liveVal = LiveSubPassThroughService.liveAppliedEqDb[i]
            eqBandLabels[i].text = "%-6s | User: %+.1f dB  (Live DSP: %+.1f dB)".format(labelName, userVal, liveVal)
        }

        val fxOn = LiveSubPassThroughService.fxEnabled
        fxToggleBtn.text = if (fxOn) "✨ POWERAMP AUDIO FX: ON" else "⚪ POWERAMP AUDIO FX: OFF"
        fxToggleBtn.background = roundedBg(if (fxOn) "#3FB950" else "#21262D", "#3FB950")
        fxToggleBtn.setTextColor(if (fxOn) Color.BLACK else Color.WHITE)

        stereoExpandLabel.text = "Stereo Widening / 3D Spatializer FX: ${LiveSubPassThroughService.stereoExpandPct}%"
        bassBoostLabel.text = "Deep Sub BassBoost FX: ${LiveSubPassThroughService.bassBoostPct}%"
        for (rIdx in reverbPresetBtns.indices) {
            stylePresetBtn(reverbPresetBtns[rIdx], LiveSubPassThroughService.reverbPreset == rIdx)
        }

        // Eclipsa Audio & Fire TV Stick Max Status Refresh
        eclipsaBridgeBadge.text = if (running) {
            "🟢 ECLIPSA 7.1.4 -> 9.1.4 BRIDGE ACTIVE • ${NowPlayingListenerService.currentAppName} (Frame #$pulse) • Audit: ${LiveSubPassThroughService.eclipsaAuditScore}/100"
        } else {
            "⚪ ECLIPSA BRIDGE STANDBY • Tap Activate below before launching YouTube on Fire TV Max"
        }
        eclipsaBridgeBadge.setTextColor(if (running) Color.parseColor("#3FB950") else Color.parseColor("#D29922"))
        eclipsaAuditVerdictText.text = LiveSubPassThroughService.eclipsaAuditVerdict
        eclipsaHdmiReportText.text = LiveSubPassThroughService.eclipsaHdmiSinkReport
        eclipsaCodecReportText.text = LiveSubPassThroughService.eclipsaCodecReport
        eclipsaChannelMapText.text = LiveSubPassThroughService.eclipsaChannelMapReport
        eclipsaTestStatusText.text = LiveSubPassThroughService.eclipsaTestStatus
    }

    private fun selectTab(tabIndex: Int) {
        activeTabIndex = tabIndex
        eclipsaTabContainer.visibility = if (tabIndex == 0) View.VISIBLE else View.GONE
        eqTabContainer.visibility = if (tabIndex == 1) View.VISIBLE else View.GONE
        fxTabContainer.visibility = if (tabIndex == 2) View.VISIBLE else View.GONE
        micTabContainer.visibility = if (tabIndex == 3) View.VISIBLE else View.GONE
        stylePresetBtn(tabEclipsaBtn, tabIndex == 0)
        stylePresetBtn(tabEqBtn, tabIndex == 1)
        stylePresetBtn(tabFxBtn, tabIndex == 2)
        stylePresetBtn(tabMicBtn, tabIndex == 3)
    }

    private fun stylePresetBtn(btn: Button, active: Boolean) {
        btn.background = roundedBg(
            if (active) "#58A6FF" else "#21262D",
            if (active) "#58A6FF" else "#30363D"
        )
        btn.setTextColor(if (active) Color.BLACK else Color.WHITE)
    }

    private fun roundedBg(fillHex: String, strokeHex: String? = null): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = 22f
            setColor(Color.parseColor(fillHex))
            if (strokeHex != null) {
                setStroke(3, Color.parseColor(strokeHex))
            }
        }

    private fun cardBg(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = 30f
        setColor(Color.parseColor("#161B22"))
        setStroke(3, Color.parseColor("#30363D"))
    }

    private fun sectionMargin(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = 28 }

    private fun buildUi(): ScrollView {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0D1117"))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 48, 36, 64)
        }

        val title = TextView(this).apply {
            text = "Eclipsa Audio & 360° Sub Guard v3.0"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        root.addView(title)

        val subtitle = TextView(this).apply {
            text = "Fire TV Stick 4K Max • YouTube IAMF 7.1.4 -> 9.1.4 Samsung Soundbar • Poweramp EQ"
            textSize = 11.5f
            setTextColor(Color.parseColor("#A371F7"))
            setPadding(0, 4, 0, 8)
        }
        root.addView(subtitle)

        statusBadge = TextView(this).apply {
            text = "○ STOPPED"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 4, 0, 18)
        }
        root.addView(statusBadge)

        // =========================================================================
        // TOP 4-TAB BAR (ECLIPSA & YT FIRST FOR FIRE TV STICK 4K MAX + REMOTE)
        // =========================================================================
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 4f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 18 }
        }
        val tabLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(3, 0, 3, 0)
        }
        tabEclipsaBtn = Button(this).apply {
            text = "🌌 Eclipsa & YT"
            textSize = 10.5f
            setTypeface(typeface, Typeface.BOLD)
            isFocusable = true
            layoutParams = tabLp
            setOnClickListener { selectTab(0) }
        }
        tabEqBtn = Button(this).apply {
            text = "🎛️ Poweramp EQ"
            textSize = 10.5f
            setTypeface(typeface, Typeface.BOLD)
            isFocusable = true
            layoutParams = tabLp
            setOnClickListener { selectTab(1) }
        }
        tabFxBtn = Button(this).apply {
            text = "✨ FX & Limiter"
            textSize = 10.5f
            setTypeface(typeface, Typeface.BOLD)
            isFocusable = true
            layoutParams = tabLp
            setOnClickListener { selectTab(2) }
        }
        tabMicBtn = Button(this).apply {
            text = "📊 Mic & Cal"
            textSize = 10.5f
            setTypeface(typeface, Typeface.BOLD)
            isFocusable = true
            layoutParams = tabLp
            setOnClickListener { selectTab(3) }
        }
        tabBar.addView(tabEclipsaBtn)
        tabBar.addView(tabEqBtn)
        tabBar.addView(tabFxBtn)
        tabBar.addView(tabMicBtn)
        root.addView(tabBar)

        eclipsaTabContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.VISIBLE
        }
        eqTabContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        fxTabContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        micTabContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        root.addView(eclipsaTabContainer)
        root.addView(eqTabContainer)
        root.addView(fxTabContainer)
        root.addView(micTabContainer)

        // =========================================================================
        // ECLIPSA TAB CARD 1: REAL ECLIPSA AUDIO AUDIT & FIRE TV MAX -> SAMSUNG BRIDGE
        // =========================================================================
        val eclipsaAuditCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg()
            setPadding(32, 28, 32, 28)
        }
        eclipsaAuditCard.addView(TextView(this).apply {
            text = "🌌 FIRE TV STICK 4K MAX -> YOUTUBE -> SAMSUNG ECLIPSA BRIDGE"
            textSize = 12.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#A371F7"))
        })
        eclipsaBridgeBadge = TextView(this).apply {
            text = "⚪ ECLIPSA BRIDGE STANDBY"
            textSize = 11.5f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.parseColor("#D29922"))
            setPadding(0, 10, 0, 14)
        }
        eclipsaAuditCard.addView(eclipsaBridgeBadge)

        eclipsaActivateBtn = Button(this).apply {
            text = "🌌 ACTIVATE ECLIPSA 7.1.4 -> 9.1.4 SAMSUNG SOUNDBAR BRIDGE"
            textSize = 12.5f
            setTypeface(typeface, Typeface.BOLD)
            background = roundedBg("#A371F7")
            setTextColor(Color.BLACK)
            isFocusable = true
            setPadding(16, 22, 16, 22)
            setOnClickListener { activateEclipsaBridge() }
        }
        eclipsaAuditCard.addView(eclipsaActivateBtn)

        eclipsaAuditBtn = Button(this).apply {
            text = "🔍 RUN REAL ECLIPSA AUDIO & HDMI eARC AUDIT"
            textSize = 12.5f
            setTypeface(typeface, Typeface.BOLD)
            background = roundedBg("#21262D", "#58A6FF")
            setTextColor(Color.parseColor("#58A6FF"))
            isFocusable = true
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 14 }
            layoutParams = lp
            setPadding(16, 20, 16, 20)
            setOnClickListener { triggerEclipsaAudit() }
        }
        eclipsaAuditCard.addView(eclipsaAuditBtn)

        eclipsaAuditVerdictText = TextView(this).apply {
            text = LiveSubPassThroughService.eclipsaAuditVerdict
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#3FB950"))
            setPadding(0, 14, 0, 4)
        }
        eclipsaAuditCard.addView(eclipsaAuditVerdictText)

        eclipsaHdmiReportText = TextView(this).apply {
            text = LiveSubPassThroughService.eclipsaHdmiSinkReport
            textSize = 11.5f
            setTextColor(Color.parseColor("#C9D1D9"))
            setPadding(0, 4, 0, 4)
        }
        eclipsaAuditCard.addView(eclipsaHdmiReportText)

        eclipsaCodecReportText = TextView(this).apply {
            text = LiveSubPassThroughService.eclipsaCodecReport
            textSize = 11.5f
            setTextColor(Color.parseColor("#8B949E"))
            setPadding(0, 2, 0, 4)
        }
        eclipsaAuditCard.addView(eclipsaCodecReportText)

        eclipsaChannelMapText = TextView(this).apply {
            text = LiveSubPassThroughService.eclipsaChannelMapReport
            textSize = 11.5f
            setTextColor(Color.parseColor("#58A6FF"))
            setPadding(0, 4, 0, 0)
        }
        eclipsaAuditCard.addView(eclipsaChannelMapText)
        eclipsaTabContainer.addView(eclipsaAuditCard)

        // =========================================================================
        // ECLIPSA TAB CARD 2: VERIFIED YOUTUBE ECLIPSA (IAMF itag 773) PLAYER
        // =========================================================================
        val eclipsaYtCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg()
            setPadding(32, 28, 32, 28)
            layoutParams = sectionMargin()
        }
        eclipsaYtCard.addView(TextView(this).apply {
            text = "🎬 PLAY VERIFIED YOUTUBE ECLIPSA AUDIO (IAMF 7.1.4)"
            textSize = 12.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        eclipsaYtCard.addView(TextView(this).apply {
            text = "Play directly inside the Built-In Eclipsa Spatial WebPlayer or launch Fire TV YouTube / SmartTube with our 7.1.4 -> 9.1.4 Eclipsa DSP locked on."
            textSize = 11.5f
            setTextColor(Color.parseColor("#8B949E"))
            setPadding(0, 4, 0, 12)
        })

        eclipsaWebView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                460
            ).apply { bottomMargin = 14 }
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36"
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            loadData(
                "<html><body style='background:#090c10;color:#8b949e;font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;text-align:center;margin:0;padding:16px;'><div><b style='color:#a371f7;'>Built-In Eclipsa Audio Spatial Player</b><br><br>Tap <b>▶ Built-In Eclipsa Player</b> below to stream any verified YouTube IAMF 7.1.4 video with 9.1.4 DSP active.</div></body></html>",
                "text/html",
                "UTF-8"
            )
        }
        eclipsaYtCard.addView(eclipsaWebView)

        val curatedYtList = listOf(
            Pair("BnbxkFx7uzc", "7.1.4 Speaker Test (Eclipsa Audio & IAMF) — Height + Surround"),
            Pair("NZtshMSQTKA", "5.1.2 Soundcheck — Eclipsa Audio & IAMF Test for Soundbars"),
            Pair("EMQBNlsTsRQ", "Eclipsa Audio / 7.1.4 Discrete Channel Check"),
            Pair("t3Bxe4CVesU", "Height Speaker Test (7.1.4 IAMF) — 4 Top Speakers Only"),
            Pair("zG44XEoA_HE", "Live Orchestra Captured in 3D Spatial Sound (Eclipsa / IAMF)")
        )
        for ((vid, vTitle) in curatedYtList) {
            eclipsaYtCard.addView(TextView(this).apply {
                text = "• $vTitle"
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor("#C9D1D9"))
                setPadding(0, 10, 0, 6)
            })
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 2f
            }
            val rLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(4, 0, 4, 6)
            }
            val inAppBtn = Button(this).apply {
                text = "▶ Built-In Eclipsa Player"
                textSize = 10.5f
                background = roundedBg("#21262D", "#A371F7")
                setTextColor(Color.parseColor("#D2A8FF"))
                isFocusable = true
                layoutParams = rLp
                setOnClickListener { playVideoInBuiltInEclipsaPlayer(vid) }
            }
            val tvAppBtn = Button(this).apply {
                text = "📺 Fire TV YouTube + DSP"
                textSize = 10.5f
                background = roundedBg("#21262D", "#3FB950")
                setTextColor(Color.parseColor("#3FB950"))
                isFocusable = true
                layoutParams = rLp
                setOnClickListener { openVideoInFireTvYouTube(vid) }
            }
            row.addView(inAppBtn)
            row.addView(tvAppBtn)
            eclipsaYtCard.addView(row)
        }
        eclipsaTabContainer.addView(eclipsaYtCard)

        // =========================================================================
        // ECLIPSA TAB CARD 3: BUILT-IN DISCRETE 12-CHANNEL ECLIPSA HARDWARE TEST
        // =========================================================================
        val eclipsaTestCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg()
            setPadding(32, 28, 32, 28)
            layoutParams = sectionMargin()
        }
        eclipsaTestCard.addView(TextView(this).apply {
            text = "🔊 BUILT-IN 12-CHANNEL ECLIPSA 7.1.4 HARDWARE SPEAKER TEST"
            textSize = 12.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#58A6FF"))
        })
        eclipsaTestStatusText = TextView(this).apply {
            text = LiveSubPassThroughService.eclipsaTestStatus
            textSize = 11.5f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.parseColor("#3FB950"))
            setPadding(0, 8, 0, 12)
        }
        eclipsaTestCard.addView(eclipsaTestStatusText)

        val testBtnConfigs = listOf(
            Triple("🔊 Play Full 12-Ch Eclipsa 7.1.4 Sequential Check (Bed + 4 Height + LFE)", "full_714", "#58A6FF"),
            Triple("☁️ Play 4-Speaker Overhead Height Only Test (TFL / TFR / TBL / TBR)", "height_only", "#A371F7"),
            Triple("🎸 Play Deep Velvet LFE Sub Test (28–62 Hz • Zero Hard Hit)", "velvet_lfe", "#3FB950")
        )
        for ((label, tid, hex) in testBtnConfigs) {
            val tBtn = Button(this).apply {
                text = label
                textSize = 11.5f
                setTypeface(typeface, Typeface.BOLD)
                background = roundedBg("#21262D", hex)
                setTextColor(Color.parseColor(hex))
                isFocusable = true
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 10 }
                layoutParams = lp
                setPadding(16, 18, 16, 18)
                setOnClickListener { triggerEclipsaTest(tid) }
            }
            eclipsaTestCard.addView(tBtn)
        }
        eclipsaTabContainer.addView(eclipsaTestCard)

        // =========================================================================
        // CARD 1: NOW PLAYING SONG DETAILS & LIVE PASS-THROUGH PROOF
        // =========================================================================
        val nowPlayingCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg()
            setPadding(32, 28, 32, 28)
        }

        passThroughBadge = TextView(this).apply {
            text = "⚪ PASS-THROUGH STANDBY"
            textSize = 11.5f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.parseColor("#3FB950"))
        }
        nowPlayingCard.addView(passThroughBadge)

        songTitleText = TextView(this).apply {
            text = "🎵 Waiting for Music Player..."
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, 12, 0, 4)
        }
        nowPlayingCard.addView(songTitleText)

        songArtistText = TextView(this).apply {
            text = "Play any song in Spotify or YouTube Music"
            textSize = 13f
            setTextColor(Color.parseColor("#8B949E"))
            setPadding(0, 0, 0, 18)
        }
        nowPlayingCard.addView(songArtistText)

        // Transport Controls Row (Prev / Play-Pause / Next)
        val transportRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 3f
        }
        val btnLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(6, 0, 6, 0)
        }

        val prevBtn = Button(this).apply {
            text = "⏮ Prev"
            textSize = 13f
            background = roundedBg("#21262D", "#30363D")
            setTextColor(Color.WHITE)
            layoutParams = btnLp
            setOnClickListener { NowPlayingListenerService.prevTrack(this@MainActivity) }
        }
        playPauseBtn = Button(this).apply {
            text = "⏯ Play/Pause"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            background = roundedBg("#3FB950")
            setTextColor(Color.BLACK)
            layoutParams = btnLp
            setOnClickListener { NowPlayingListenerService.playPause(this@MainActivity) }
        }
        val nextBtn = Button(this).apply {
            text = "⏭ Next"
            textSize = 13f
            background = roundedBg("#21262D", "#30363D")
            setTextColor(Color.WHITE)
            layoutParams = btnLp
            setOnClickListener { NowPlayingListenerService.nextTrack(this@MainActivity) }
        }
        transportRow.addView(prevBtn)
        transportRow.addView(playPauseBtn)
        transportRow.addView(nextBtn)
        nowPlayingCard.addView(transportRow)

        enableNotifBtn = Button(this).apply {
            text = "🔔 Enable Song Title & Artist Sync (1-Tap Permission)"
            textSize = 12f
            background = roundedBg("#1F2937", "#58A6FF")
            setTextColor(Color.parseColor("#58A6FF"))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 16 }
            layoutParams = lp
            setOnClickListener { openNotificationAccessSettings() }
        }
        nowPlayingCard.addView(enableNotifBtn)

        bypassBtn = Button(this).apply {
            text = "🔊 A/B TEST: 360° PREMIUM DSP ON (Tap to Compare Raw)"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 14 }
            layoutParams = lp
            setOnClickListener {
                LiveSubPassThroughService.isBypassed = !LiveSubPassThroughService.isBypassed
                refreshMeters()
            }
        }
        nowPlayingCard.addView(bypassBtn)

        // =========================================================================
        // CARD 2: MASTER START / STOP & 4 ONE-TAP PREMIUM SOUND PROFILES
        // =========================================================================
        val masterCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg()
            setPadding(32, 28, 32, 28)
            layoutParams = sectionMargin()
        }

        toggleBtn = Button(this).apply {
            text = "▶ START 360° LIVE SOUND & SUB OPTIMIZER"
            textSize = 14.5f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(20, 28, 20, 28)
            setOnClickListener { toggleService() }
        }
        masterCard.addView(toggleBtn)

        val presetTitle = TextView(this).apply {
            text = "ONE-TAP 360° SOUND PROFILES (NO HARD BASS HIT):"
            textSize = 11.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#8B949E"))
            setPadding(0, 22, 0, 10)
        }
        masterCard.addView(presetTitle)

        val presetRow1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }
        val halfLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(6, 6, 6, 6)
        }

        presetVelvetBtn = Button(this).apply {
            text = "✨ Deep Velvet\n(No Hard Hit)"
            textSize = 12f
            layoutParams = halfLp
            setOnClickListener {
                LiveSubPassThroughService.applyPreset("DEEP_VELVET")
                refreshMeters()
            }
        }
        presetSurroundBtn = Button(this).apply {
            text = "🎭 360° Surround\n(Front + Rear)"
            textSize = 12f
            layoutParams = halfLp
            setOnClickListener {
                LiveSubPassThroughService.applyPreset("SURROUND_360")
                refreshMeters()
            }
        }
        presetRow1.addView(presetVelvetBtn)
        presetRow1.addView(presetSurroundBtn)
        masterCard.addView(presetRow1)

        val presetRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }
        presetVocalBtn = Button(this).apply {
            text = "💎 Pure Vocal\n& Silk Highs"
            textSize = 12f
            layoutParams = halfLp
            setOnClickListener {
                LiveSubPassThroughService.applyPreset("PURE_VOCAL")
                refreshMeters()
            }
        }
        presetLoudnessBtn = Button(this).apply {
            text = "🔥 Low-Volume\nRich Loudness"
            textSize = 12f
            layoutParams = halfLp
            setOnClickListener {
                LiveSubPassThroughService.applyPreset("LOUDNESS_RICH")
                refreshMeters()
            }
        }
        presetRow2.addView(presetVocalBtn)
        presetRow2.addView(presetLoudnessBtn)
        masterCard.addView(presetRow2)

        // =========================================================================
        // CARD 3: REAL-TIME 5-ZONE ALL-SPEAKER MICROPHONE METERS (TAB 3)
        // =========================================================================
        val meterCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg()
            setPadding(32, 28, 32, 28)
            layoutParams = sectionMargin()
        }

        val meterHeader = TextView(this).apply {
            text = "LIVE ALL-SPEAKER MICROPHONE ANALYZER (5 ZONES)"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#58A6FF"))
            setPadding(0, 0, 0, 12)
        }
        meterCard.addView(meterHeader)

        // Zone 1: Deep Background Sub (28-62 Hz)
        val deepLabel = TextView(this).apply {
            text = "1. DEEP BACKGROUND SUB-BASS (28 - 62 Hz • Velvet Cushion)"
            textSize = 11.5f
            setTextColor(Color.parseColor("#8B949E"))
        }
        meterCard.addView(deepLabel)

        deepSubValText = TextView(this).apply {
            text = "42.0 dB  (Velvet Boost: +4.0 dB)"
            textSize = 18f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.parseColor("#3FB950"))
        }
        meterCard.addView(deepSubValText)

        deepSubProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 25
        }
        meterCard.addView(deepSubProgress)

        // Zone 2: Upper-Bass Hard-Hit Zone (70-140 Hz)
        val hitLabel = TextView(this).apply {
            text = "2. UPPER-BASS SLAM / HARD-HIT ZONE (70 - 140 Hz • Suppressed)"
            textSize = 11.5f
            setTextColor(Color.parseColor("#8B949E"))
            setPadding(0, 18, 0, 0)
        }
        meterCard.addView(hitLabel)

        hitBassValText = TextView(this).apply {
            text = "42.0 dB"
            textSize = 18f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        meterCard.addView(hitBassValText)

        hitBassProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 20
        }
        meterCard.addView(hitBassProgress)

        cutValText = TextView(this).apply {
            text = "-3.5 dB Anti-Hit Cut"
            textSize = 20f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.parseColor("#FF7B00"))
            setPadding(0, 6, 0, 0)
        }
        meterCard.addView(cutValText)

        cutProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 30
        }
        meterCard.addView(cutProgress)

        // Zone 3: Front & Rear Speakers (Vocals & Surround Stage)
        val midLabel = TextView(this).apply {
            text = "3. FRONT COMPONENT + REAR COAXIAL STAGE (200 - 2800 Hz)"
            textSize = 11.5f
            setTextColor(Color.parseColor("#8B949E"))
            setPadding(0, 18, 0, 0)
        }
        meterCard.addView(midLabel)

        midValText = TextView(this).apply {
            text = "42.0 dB  (360° Stage: +2.5 dB)"
            textSize = 17f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.parseColor("#58A6FF"))
        }
        meterCard.addView(midValText)

        midProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 25
        }
        meterCard.addView(midProgress)

        // Zone 4: Tweeters & Distortion Guard
        highValText = TextView(this).apply {
            text = "4. Tweeter Highs (3.2 - 12 kHz): 38.0 dB (Silk Trim: -1.0 dB)"
            textSize = 12.5f
            setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
            setTextColor(Color.parseColor("#C9D1D9"))
            setPadding(0, 16, 0, 4)
        }
        meterCard.addView(highValText)

        thdBadgeText = TextView(this).apply {
            text = "Distortion Guard: 0.2% THD • 100% CLIP-FREE HEADROOM"
            textSize = 12f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.parseColor("#3FB950"))
        }
        meterCard.addView(thdBadgeText)
        micTabContainer.addView(meterCard)

        // =========================================================================
        // CARD 4: ONE-TAP 360° ALL-SPEAKER AUTO-CALIBRATION (FRONT + REAR + SUB)
        // =========================================================================
        val calCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg()
            setPadding(32, 28, 32, 28)
            layoutParams = sectionMargin()
        }

        val calHeader = TextView(this).apply {
            text = "🎯 360° ALL-SPEAKER AUTO-CALIBRATION (MIC SWEEP)"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        calCard.addView(calHeader)

        calStatusText = TextView(this).apply {
            text = LiveSubPassThroughService.calibrationStatus
            textSize = 12f
            setTextColor(Color.parseColor("#58A6FF"))
            setPadding(0, 6, 0, 8)
        }
        calCard.addView(calStatusText)

        calProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            setPadding(0, 0, 0, 14)
        }
        calCard.addView(calProgressBar)

        autoCalBtn = Button(this).apply {
            text = "🎯 AUTO-CALIBRATE FRONT + REAR + SUBWOOFER (4-SEC SWEEP)"
            textSize = 12.5f
            setTypeface(typeface, Typeface.BOLD)
            background = roundedBg("#58A6FF")
            setTextColor(Color.BLACK)
            setPadding(18, 22, 18, 22)
            setOnClickListener { triggerAutoCalibration() }
        }
        calCard.addView(autoCalBtn)

        calReportText = TextView(this).apply {
            text = LiveSubPassThroughService.calRecommendation
            textSize = 12f
            setTextColor(Color.parseColor("#C9D1D9"))
            setPadding(0, 14, 0, 0)
        }
        calCard.addView(calReportText)
        micTabContainer.addView(calCard)

        // =========================================================================
        // CARD 4B: POWERAMP-STYLE 10-BAND PASS-THROUGH EQ, TONE & LIMITER FOR SPOTIFY
        // =========================================================================
        val powerampCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg()
            setPadding(32, 28, 32, 28)
            layoutParams = sectionMargin()
        }

        val paHeader = TextView(this).apply {
            text = "🎛️ POWERAMP-STYLE SPOTIFY PASS-THROUGH EQ, TONE & LIMITER"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#3FB950"))
        }
        powerampCard.addView(paHeader)

        val paDesc = TextView(this).apply {
            text = "Fine-tune Spotify / YouTube Music live with a 10-Band EQ, Bass/Treble Tone, Pre-Amp Headroom & Anti-Clip Limiter for crystal-clear vocals + deep distortion-free bass."
            textSize = 11.5f
            setTextColor(Color.parseColor("#8B949E"))
            setPadding(0, 4, 0, 12)
        }
        powerampCard.addView(paDesc)

        optimizeSpotifyBtn = Button(this).apply {
            text = "🎧 MEASURE & OPTIMIZE PLAYING SPOTIFY SONG (AUTO-EQ)"
            textSize = 12.5f
            setTypeface(typeface, Typeface.BOLD)
            background = roundedBg("#3FB950")
            setTextColor(Color.BLACK)
            setPadding(18, 22, 18, 22)
            setOnClickListener { triggerSpotifyOptimize() }
        }
        powerampCard.addView(optimizeSpotifyBtn)

        spotifyOptStatusText = TextView(this).apply {
            text = LiveSubPassThroughService.musicOptStatus
            textSize = 11.5f
            setTextColor(Color.parseColor("#58A6FF"))
            setPadding(0, 10, 0, 14)
        }
        powerampCard.addView(spotifyOptStatusText)

        // Toggle Row: Live Auto-EQ ON/OFF + Limiter ON/OFF
        val paToggleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }
        val paHalfLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(6, 0, 6, 14)
        }
        autoEqToggleBtn = Button(this).apply {
            text = "🤖 LIVE AUTO-EQ: ON"
            textSize = 11.5f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = paHalfLp
            setOnClickListener {
                LiveSubPassThroughService.autoPowerampOptimize = !LiveSubPassThroughService.autoPowerampOptimize
                refreshMeters()
            }
        }
        limiterToggleBtn = Button(this).apply {
            text = "🛡️ LIMITER: ON"
            textSize = 11.5f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = paHalfLp
            setOnClickListener {
                LiveSubPassThroughService.limiterEnabled = !LiveSubPassThroughService.limiterEnabled
                refreshMeters()
            }
        }
        paToggleRow.addView(autoEqToggleBtn)
        paToggleRow.addView(limiterToggleBtn)
        powerampCard.addView(paToggleRow)

        // Tone & Limiter Sliders
        toneBassLabel = TextView(this).apply {
            text = "Bass Tone (31–62 Hz Deep Sub): +2.5 dB"
            textSize = 12f
            setTextColor(Color.WHITE)
        }
        powerampCard.addView(toneBassLabel)
        toneBassSeek = SeekBar(this).apply {
            max = 24 // -6.0 to +6.0 dB in 0.5 steps
            progress = 17
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) {
                        LiveSubPassThroughService.toneBassDb = (p - 12) * 0.5f
                    }
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        powerampCard.addView(toneBassSeek)

        toneTrebleLabel = TextView(this).apply {
            text = "Treble Tone (8–16 kHz Silk Air): +0.0 dB"
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(0, 12, 0, 0)
        }
        powerampCard.addView(toneTrebleLabel)
        toneTrebleSeek = SeekBar(this).apply {
            max = 24
            progress = 12
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) {
                        LiveSubPassThroughService.toneTrebleDb = (p - 12) * 0.5f
                    }
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        powerampCard.addView(toneTrebleSeek)

        preampLabel = TextView(this).apply {
            text = "Pre-Amp Headroom (Anti-Clip): -2.0 dB"
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(0, 12, 0, 0)
        }
        powerampCard.addView(preampLabel)
        preampSeek = SeekBar(this).apply {
            max = 16 // -8.0 to 0.0 dB in 0.5 steps
            progress = 12
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) {
                        LiveSubPassThroughService.preampDb = (p - 16) * 0.5f
                    }
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        powerampCard.addView(preampSeek)

        limiterCeilingLabel = TextView(this).apply {
            text = "Limiter Ceiling: -1.2 dBFS"
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(0, 12, 0, 0)
        }
        powerampCard.addView(limiterCeilingLabel)
        limiterCeilingSeek = SeekBar(this).apply {
            max = 27 // -3.0 to -0.3 dBFS in 0.1 steps
            progress = 18
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) {
                        LiveSubPassThroughService.limiterCeilingDb = -3.0f + (p * 0.1f)
                    }
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        powerampCard.addView(limiterCeilingSeek)

        val eqSectionTitle = TextView(this).apply {
            text = "10-BAND GRAPHIC / PARAMETRIC EQUALIZER (-10 to +6 dB)"
            textSize = 11.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#58A6FF"))
            setPadding(0, 20, 0, 8)
        }
        powerampCard.addView(eqSectionTitle)

        eqBandLabels.clear()
        eqBandSeeks.clear()
        for (idx in 0 until 10) {
            val lbl = TextView(this).apply {
                text = "%-6s | User: %+.1f dB".format(
                    LiveSubPassThroughService.EQ_LABELS[idx],
                    LiveSubPassThroughService.userEqOffsetsDb[idx]
                )
                textSize = 11.5f
                setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
                setTextColor(Color.parseColor("#C9D1D9"))
                setPadding(0, 8, 0, 0)
            }
            val sb = SeekBar(this).apply {
                max = 32 // -10.0 to +6.0 dB in 0.5 dB steps
                progress = ((LiveSubPassThroughService.userEqOffsetsDb[idx] + 10.0f) * 2.0f).toInt().coerceIn(0, 32)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                        if (fromUser) {
                            LiveSubPassThroughService.userEqOffsetsDb[idx] = -10.0f + (p * 0.5f)
                        }
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }
            eqBandLabels.add(lbl)
            eqBandSeeks.add(sb)
            powerampCard.addView(lbl)
            powerampCard.addView(sb)
        }

        val resetPaBtn = Button(this).apply {
            text = "↺ Reset to Crystal Clear Vocals + Deep Bass Preset"
            textSize = 12f
            background = roundedBg("#21262D", "#58A6FF")
            setTextColor(Color.parseColor("#58A6FF"))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 16 }
            layoutParams = lp
            setOnClickListener {
                LiveSubPassThroughService.resetPowerampCrystalBassPreset()
                toneBassSeek.progress = 17
                toneTrebleSeek.progress = 12
                preampSeek.progress = 12
                limiterCeilingSeek.progress = 18
                for (i in 0 until 10) {
                    eqBandSeeks[i].progress = ((LiveSubPassThroughService.userEqOffsetsDb[i] + 10.0f) * 2.0f).toInt().coerceIn(0, 32)
                }
                stereoExpandSeek.progress = LiveSubPassThroughService.stereoExpandPct
                bassBoostSeek.progress = LiveSubPassThroughService.bassBoostPct
                refreshMeters()
            }
        }
        powerampCard.addView(resetPaBtn)
        eqTabContainer.addView(powerampCard)
        nowPlayingCard.layoutParams = sectionMargin()
        eqTabContainer.addView(nowPlayingCard)
        eqTabContainer.addView(masterCard)

        // =========================================================================
        // CARD 4C: POWERAMP AUDIO FX (STEREO EXPAND + DEEP BASSBOOST + REVERB) (TAB 2)
        // =========================================================================
        val fxCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg()
            setPadding(32, 28, 32, 28)
            layoutParams = sectionMargin()
        }

        val fxHeader = TextView(this).apply {
            text = "✨ POWERAMP AUDIO FX ENGINE (STEREO • SUB • REVERB)"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#3FB950"))
        }
        fxCard.addView(fxHeader)

        val fxDesc = TextView(this).apply {
            text = "Hardware Stereo Widening (Virtualizer), Deep Sub BassBoost FX, and Room Ambience Reverb applied live to Spotify & YouTube Music."
            textSize = 11.5f
            setTextColor(Color.parseColor("#8B949E"))
            setPadding(0, 4, 0, 14)
        }
        fxCard.addView(fxDesc)

        fxToggleBtn = Button(this).apply {
            text = "✨ POWERAMP AUDIO FX: ON"
            textSize = 12.5f
            setTypeface(typeface, Typeface.BOLD)
            setOnClickListener {
                LiveSubPassThroughService.fxEnabled = !LiveSubPassThroughService.fxEnabled
                refreshMeters()
            }
        }
        fxCard.addView(fxToggleBtn)

        stereoExpandLabel = TextView(this).apply {
            text = "Stereo Widening / 3D Spatializer FX: 35%"
            textSize = 12.5f
            setTextColor(Color.WHITE)
            setPadding(0, 18, 0, 0)
        }
        fxCard.addView(stereoExpandLabel)
        stereoExpandSeek = SeekBar(this).apply {
            max = 100
            progress = LiveSubPassThroughService.stereoExpandPct
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) LiveSubPassThroughService.stereoExpandPct = p
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        fxCard.addView(stereoExpandSeek)

        bassBoostLabel = TextView(this).apply {
            text = "Deep Sub BassBoost FX: 30%"
            textSize = 12.5f
            setTextColor(Color.WHITE)
            setPadding(0, 16, 0, 0)
        }
        fxCard.addView(bassBoostLabel)
        bassBoostSeek = SeekBar(this).apply {
            max = 100
            progress = LiveSubPassThroughService.bassBoostPct
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) LiveSubPassThroughService.bassBoostPct = p
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        fxCard.addView(bassBoostSeek)

        val reverbTitle = TextView(this).apply {
            text = "ROOM AMBIENCE / REVERB FX PRESET:"
            textSize = 11.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#58A6FF"))
            setPadding(0, 18, 0, 8)
        }
        fxCard.addView(reverbTitle)

        reverbPresetBtns.clear()
        val reverbNames = arrayOf("None", "Small Room", "Med Room", "Large Hall", "Plate")
        val reverbRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 5f
        }
        val revLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(3, 0, 3, 0)
        }
        for (rIdx in reverbNames.indices) {
            val rBtn = Button(this).apply {
                text = reverbNames[rIdx]
                textSize = 10f
                layoutParams = revLp
                setPadding(4, 14, 4, 14)
                setOnClickListener {
                    LiveSubPassThroughService.reverbPreset = rIdx
                    refreshMeters()
                }
            }
            reverbPresetBtns.add(rBtn)
            reverbRow.addView(rBtn)
        }
        fxCard.addView(reverbRow)
        fxTabContainer.addView(fxCard)

        // =========================================================================
        // CARD 5: FINE-TUNE 360° SOUND & DEEP VELVET SUB SLIDERS
        // =========================================================================
        val sliderCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg()
            setPadding(32, 28, 32, 28)
            layoutParams = sectionMargin()
        }

        // Slider 1: Deep Background Sub Warmth (28-60 Hz)
        deepWarmthLabel = TextView(this).apply {
            text = "Deep Background Sub Warmth (28-60 Hz): +4.5 dB"
            textSize = 12.5f
            setTextColor(Color.WHITE)
        }
        sliderCard.addView(deepWarmthLabel)

        val deepSeek = SeekBar(this).apply {
            max = 16 // 0.0 to +8.0 dB
            progress = 9
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val db = p * 0.5f
                    LiveSubPassThroughService.deepSubWarmthDb = db
                    deepWarmthLabel.text = "Deep Background Sub Warmth (28-60 Hz): %+.1f dB".format(db)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        sliderCard.addView(deepSeek)

        // Slider 2: Upper-Bass Anti-Hit Sensitivity
        threshValueLabel = TextView(this).apply {
            text = "Upper-Bass Anti-Hit Ceiling (70-140 Hz): +2.5 dB over Mids"
            textSize = 12.5f
            setTextColor(Color.WHITE)
            setPadding(0, 18, 0, 0)
        }
        sliderCard.addView(threshValueLabel)

        val threshSeek = SeekBar(this).apply {
            max = 14
            progress = 5
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val db = (p - 2).toFloat()
                    LiveSubPassThroughService.targetBassOverMidsDb = db
                    threshValueLabel.text = "Upper-Bass Anti-Hit Ceiling (70-140 Hz): %+.1f dB over Mids".format(db)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        sliderCard.addView(threshSeek)

        // Slider 3: Max Anti-Hit Cut Limit
        maxCutValueLabel = TextView(this).apply {
            text = "Max Hard-Hit Suppression Limit: -12 dB"
            textSize = 12.5f
            setTextColor(Color.WHITE)
            setPadding(0, 18, 0, 0)
        }
        sliderCard.addView(maxCutValueLabel)

        val maxCutSeek = SeekBar(this).apply {
            max = 12
            progress = 9 // 3..15 dB
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val db = (p + 3).toFloat()
                    LiveSubPassThroughService.maxSubCutDb = db
                    maxCutValueLabel.text = "Max Hard-Hit Suppression Limit: -%.0f dB".format(db)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        sliderCard.addView(maxCutSeek)

        // Slider 4: Front + Rear 360° Vocal & Surround Stage
        surroundLabel = TextView(this).apply {
            text = "Front + Rear 360° Vocal Clarity: +2.5 dB"
            textSize = 12.5f
            setTextColor(Color.WHITE)
            setPadding(0, 18, 0, 0)
        }
        sliderCard.addView(surroundLabel)

        val surroundSeek = SeekBar(this).apply {
            max = 12 // 0.0 to +6.0 dB
            progress = 5
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val db = p * 0.5f
                    LiveSubPassThroughService.surroundClarityDb = db
                    surroundLabel.text = "Front + Rear 360° Vocal Clarity: %+.1f dB".format(db)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        sliderCard.addView(surroundSeek)

        // Slider 5: Tweeter Anti-Harshness / Silk Smoothness
        tweeterLabel = TextView(this).apply {
            text = "Tweeter Anti-Harshness & Silk Control: 2.5 dB"
            textSize = 12.5f
            setTextColor(Color.WHITE)
            setPadding(0, 18, 0, 0)
        }
        sliderCard.addView(tweeterLabel)

        val tweeterSeek = SeekBar(this).apply {
            max = 10 // 0.0 to 5.0 dB
            progress = 5
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val db = p * 0.5f
                    LiveSubPassThroughService.tweeterSmoothDb = db
                    tweeterLabel.text = "Tweeter Anti-Harshness & Silk Control: %.1f dB".format(db)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        sliderCard.addView(tweeterSeek)
        fxTabContainer.addView(sliderCard)

        // =========================================================================
        // CARD 6: ANDROID AUTO LAUNCHER SETUP FOR PIONEER DMH-Z5290BT
        // =========================================================================
        val autoCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg()
            setPadding(32, 28, 32, 28)
            layoutParams = sectionMargin()
        }

        val autoHeader = TextView(this).apply {
            text = "🚗 SHOW THIS APP ON ANDROID AUTO CAR SCREEN"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#3FB950"))
        }
        autoCard.addView(autoHeader)

        val autoNote = TextView(this).apply {
            text = "Note: Even before adding the icon to your car dashboard, the 360° Live Sound & Sub Optimizer ALREADY filters all Spotify & YouTube Music audio playing over Android Auto!\n\nTo make the '360° Sound & Sub Guard' icon appear on your Pioneer DMH-Z5290BT screen (required by Google for APKs installed outside Play Store):\n1. Tap the button below to open Android Auto Settings.\n2. Scroll down and tap 'Version' 10 times to unlock Developer Mode.\n3. Tap the 3-dots menu (top right) -> 'Developer settings' -> Check 'Unknown sources'."
            textSize = 12f
            setTextColor(Color.parseColor("#C9D1D9"))
            setPadding(0, 8, 0, 16)
        }
        autoCard.addView(autoNote)

        val autoSettingsBtn = Button(this).apply {
            text = "🚗 Open Android Auto Settings (Enable Car Launcher Icon)"
            textSize = 12.5f
            setTypeface(typeface, Typeface.BOLD)
            background = roundedBg("#21262D", "#3FB950")
            setTextColor(Color.parseColor("#3FB950"))
            setOnClickListener { openAndroidAutoSettings() }
        }
        autoCard.addView(autoSettingsBtn)
        micTabContainer.addView(autoCard)

        selectTab(0)
        scroll.addView(root)
        return scroll
    }
}
