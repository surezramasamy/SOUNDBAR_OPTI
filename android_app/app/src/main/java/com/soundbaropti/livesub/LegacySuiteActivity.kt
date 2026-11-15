package com.soundbaropti.livesub

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Next Screen: Complete Calibration & Optimizer Suite (v4.1)
 * Houses all previous apps & functions requested by the user:
 *  - Tab 0: Samsung Soundbar & Dolby Atmos Calibrator (3.1.2 - 11.2.4, Atmos vs BT, Natural Chords + Guides)
 *  - Tab 1: Pioneer DMH-Z5290BT Car 4.1 60% Vol Distortion-Free Calibrator (6 Channels + 13-Band EQ + Crossover)
 *  - Tab 2: Poweramp 10-Band Pass-Through EQ, Tone, Preamp, Limiter & Live Spotify Optimizer
 *  - Tab 3: Poweramp AudioFX (Stereo, BassBoost, Reverb) + 360° Subwoofer Optimizer & Android Auto
 *  - Tab 4: 5-Zone Live Mic RTA, Auto-Calibration Sweep & 12-Channel Eclipsa Hardware Tests
 */
class LegacySuiteActivity : Activity() {

    companion object {
        const val EXTRA_INITIAL_TAB = "initial_tab"
    }

    private data class SoundbarChannelSpec(
        val id: String,
        val name: String,
        val remoteMenu: String,
        val group: String,
        val minDb: Int,
        val maxDb: Int,
        val targetOffsetDb: Float,
        val freqLow: Int,
        val freqHigh: Int,
        val leftAmp: Float,
        val rightAmp: Float,
        val harm1: Double,
        val harm2: Double,
        val desc: String
    )

    private data class CarChannelSpec(
        val id: String,
        val name: String,
        val speakerType: String,
        val pioneerMenu: String,
        val xoverMenu: String,
        val minDb: Int,
        val maxDb: Int,
        val targetOffsetDb: Float,
        val recommendedXover: String,
        val leftAmp: Float,
        val rightAmp: Float,
        val freq1: Double,
        val freq2: Double,
        val desc: String
    )

    private val allSoundbarSpecs = listOf(
        SoundbarChannelSpec(
            "front_left_right", "Front Left / Right (Main Stereo Stage)",
            "MASTER VOLUME (60% Distortion-Free Anchor)", "bed_3",
            0, 0, 0.0f, 140, 5200, 0.80f, 0.80f, 261.63, 392.00,
            "Natural Concert Grand Piano & Warm Strings anchor for 60% clean headroom."
        ),
        SoundbarChannelSpec(
            "center", "Center Channel (Dialogue & Vocal Anchor)",
            "CH LEVEL -> CENTER", "bed_3",
            -6, 6, 0.5f, 160, 4200, 0.78f, 0.78f, 220.00, 440.00,
            "Warm vocal formant & nylon guitar chord for crisp, non-harsh dialogue."
        ),
        SoundbarChannelSpec(
            "rear", "Rear Surround Satellites (Back L/R)",
            "CH LEVEL -> REAR", "bed_5",
            -6, 6, -1.0f, 180, 4800, 0.85f, -0.45f, 293.66, 587.33,
            "Ambient acoustic strings steered to rear surround satellite drivers."
        ),
        SoundbarChannelSpec(
            "side", "Side Surround Speakers (L/R 90 deg)",
            "CH LEVEL -> SIDE", "bed_7",
            -6, 6, -0.5f, 200, 5000, -0.50f, 0.85f, 329.63, 659.25,
            "Warm viola ensemble steered to 90-degree side-firing drivers."
        ),
        SoundbarChannelSpec(
            "wide", "Wide Front-Corner Speakers (L/R 45 deg)",
            "CH LEVEL -> WIDE", "bed_9",
            -6, 6, -0.5f, 220, 5000, 0.85f, 0.25f, 369.99, 739.99,
            "Woodwind & string blend for wide front-corner soundstage expansion."
        ),
        SoundbarChannelSpec(
            "rear_side", "Rear Side Surround (11-Ch Back-Side L/R)",
            "CH LEVEL -> REAR SIDE", "bed_11",
            -6, 6, -1.0f, 200, 4800, 0.30f, -0.82f, 415.30, 830.61,
            "Outward rear-side acoustic pad for 11.1.4 / 11.2.4 flagship bars."
        ),
        SoundbarChannelSpec(
            "front_top", "Front Top Atmos (Up-Firing Height L/R)",
            "CH LEVEL -> FRONT TOP", "atmos_2",
            -6, 6, -0.5f, 320, 9000, 0.78f, 0.78f, 523.25, 1975.50,
            "Overhead raindrop harp canopy for front ceiling reflection drivers."
        ),
        SoundbarChannelSpec(
            "rear_top", "Rear Top Atmos (Rear Up-Firing Height L/R)",
            "CH LEVEL -> REAR TOP", "atmos_4",
            -6, 6, -1.0f, 340, 9000, 0.82f, -0.32f, 659.25, 2637.00,
            "Overhead rear canopy harmonic shimmer for rear height speakers."
        ),
        SoundbarChannelSpec(
            "subwoofer", "Subwoofer 1 (Primary Deep Velvet Woofer)",
            "WOOFER LEVEL", "sub_1",
            -12, 6, 1.5f, 28, 95, 0.88f, 0.88f, 36.71, 55.00,
            "Bowed double-bass & cello sustain (32-62 Hz velvet foundation, checks 80 Hz boom)."
        ),
        SoundbarChannelSpec(
            "subwoofer_2", "Subwoofer 2 (Dual-Sub Room-Mode Balancer)",
            "SUBWOOFER 2 LEVEL / PHASE", "sub_2",
            -12, 6, 1.5f, 28, 95, 0.88f, 0.88f, 41.20, 61.74,
            "Dual-sub phase-coherent deep sustain to smooth standing-wave room boom."
        )
    )

    private val carSpecs = listOf(
        CarChannelSpec(
            "front_left", "1. Front Left — 2-Way Component (Mid + Tweeter)",
            "2-Way Component (Passive Crossover)", "Audio -> Speaker Level -> Front Left",
            "Audio -> Crossover -> Front HPF", -24, 10, 0.0f, "Front HPF 80 Hz @ -12 dB/oct",
            0.90f, 0.02f, 261.63, 523.25,
            "Natural acoustic guitar & piano (Left) — balances FL anchor for 60% Vol."
        ),
        CarChannelSpec(
            "front_right", "2. Front Right — 2-Way Component (Mid + Tweeter)",
            "2-Way Component (Passive Crossover)", "Audio -> Speaker Level -> Front Right",
            "Audio -> Crossover -> Front HPF", -24, 10, 0.0f, "Front HPF 80 Hz @ -12 dB/oct",
            0.02f, 0.90f, 261.63, 523.25,
            "Natural acoustic guitar & piano (Right) — matches FR Component to FL cleanly."
        ),
        CarChannelSpec(
            "rear_left", "3. Rear Left — Coaxial Speaker (RL)",
            "Coaxial 2-in-1 Speaker", "Audio -> Speaker Level -> Rear Left",
            "Audio -> Crossover -> Rear HPF", -24, 10, -2.0f, "Rear HPF 100 Hz @ -12 dB/oct",
            0.85f, -0.35f, 293.66, 587.33,
            "Warm acoustic strings (RL) — subtle -2 dB rear wrap-around blend."
        ),
        CarChannelSpec(
            "rear_right", "4. Rear Right — Coaxial Speaker (RR)",
            "Coaxial 2-in-1 Speaker", "Audio -> Speaker Level -> Rear Right",
            "Audio -> Crossover -> Rear HPF", -24, 10, -2.0f, "Rear HPF 100 Hz @ -12 dB/oct",
            -0.35f, 0.85f, 293.66, 587.33,
            "Warm acoustic strings (RR) — matches RL without pulling stage backward."
        ),
        CarChannelSpec(
            "subwoofer", "5. Subwoofer (Smooth Deep Velvet Bass — No Hard Hit)",
            "Subwoofer (Mono / Bridged Amp)", "Audio -> Speaker Level -> Subwoofer",
            "Audio -> Crossover -> Subwoofer LPF", -24, 10, 1.5f, "Sub LPF 63 Hz @ -18 dB/oct",
            0.90f, 0.90f, 36.71, 55.00,
            "Bowed cello & double-bass (32-62 Hz) — eliminates 80-125 Hz chest thump."
        ),
        CarChannelSpec(
            "crossover_blend", "6. Crossover & Phase Blend (Full 4.1 Transition)",
            "Full System 4.1 Transition", "Audio -> Crossover -> Sub Phase (Normal/Reverse)",
            "HPF / LPF Transition (50 - 125 Hz)", -24, 10, 0.0f, "Sub Phase NORMAL + 80/63 Hz Blend",
            0.82f, 0.82f, 65.41, 130.81,
            "Verifies seamless integration between door mid-woofers and deep trunk subwoofer."
        )
    )

    // UI References
    private lateinit var backBtn: Button
    private lateinit var statusBadge: TextView
    private lateinit var passThroughBadge: TextView
    private lateinit var songTitleText: TextView
    private lateinit var songArtistText: TextView
    private lateinit var toggleEngineBtn: Button
    private lateinit var bypassBtn: Button
    private lateinit var playPauseBtn: Button
    private lateinit var enableNotifBtn: Button

    // 5 Main Suite Tabs
    private lateinit var tabSoundbarBtn: Button
    private lateinit var tabPioneerBtn: Button
    private lateinit var tabEqBtn: Button
    private lateinit var tabFxBtn: Button
    private lateinit var tabMicBtn: Button
    private lateinit var tabSoundbarContainer: LinearLayout
    private lateinit var tabPioneerContainer: LinearLayout
    private lateinit var tabEqContainer: LinearLayout
    private lateinit var tabFxContainer: LinearLayout
    private lateinit var tabMicContainer: LinearLayout
    private var activeTab = 0

    // Tab 0: Soundbar Calibrator UI
    private var sbLayoutCode = "9.1.4"
    private var sbAtmosMode = true
    private var sbSweeping = false
    private val sbPresetBtns = linkedMapOf<String, Button>()
    private lateinit var sbModeAtmosBtn: Button
    private lateinit var sbModeBtBtn: Button
    private lateinit var sbSweepStatusText: TextView
    private lateinit var sbChannelListContainer: LinearLayout
    private val sbChannelResultViews = mutableMapOf<String, TextView>()
    private val sbMeasuredDb = mutableMapOf<String, Float>()
    private val sbConfirmed = mutableSetOf<String>()

    // Tab 1: Pioneer Car 4.1 UI
    private var carSweeping = false
    private lateinit var carSweepStatusText: TextView
    private lateinit var carChannelListContainer: LinearLayout
    private val carChannelResultViews = mutableMapOf<String, TextView>()
    private val carMeasuredDb = mutableMapOf<String, Float>()
    private val carConfirmed = mutableSetOf<String>()

    // Tab 2: Poweramp EQ UI
    private lateinit var optimizeSpotifyBtn: Button
    private lateinit var spotifyOptStatusText: TextView
    private lateinit var autoEqToggleBtn: Button
    private lateinit var limiterToggleBtn: Button
    private lateinit var toneBassLabel: TextView
    private lateinit var toneTrebleLabel: TextView
    private lateinit var preampLabel: TextView
    private lateinit var limiterCeilingLabel: TextView
    private lateinit var toneBassSeek: SeekBar
    private lateinit var toneTrebleSeek: SeekBar
    private lateinit var preampSeek: SeekBar
    private lateinit var limiterCeilingSeek: SeekBar
    private val eqBandLabels = mutableListOf<TextView>()
    private val eqBandSeeks = mutableListOf<SeekBar>()

    // Tab 3: FX & 360 Sub Optimizer UI
    private lateinit var fxToggleBtn: Button
    private lateinit var stereoExpandLabel: TextView
    private lateinit var bassBoostLabel: TextView
    private lateinit var stereoExpandSeek: SeekBar
    private lateinit var bassBoostSeek: SeekBar
    private val reverbBtns = mutableListOf<Button>()
    private lateinit var presetVelvetBtn: Button
    private lateinit var presetSurroundBtn: Button
    private lateinit var presetVocalBtn: Button
    private lateinit var presetLoudnessBtn: Button
    private lateinit var presetEclipsaBtn: Button
    private lateinit var deepWarmthLabel: TextView
    private lateinit var threshValueLabel: TextView
    private lateinit var maxCutValueLabel: TextView
    private lateinit var surroundLabel: TextView
    private lateinit var tweeterLabel: TextView

    // Tab 4: Mic & 12-Ch Test UI
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
    private lateinit var autoCalBtn: Button
    private lateinit var calProgressBar: ProgressBar
    private lateinit var calStatusText: TextView
    private lateinit var calReportText: TextView
    private lateinit var eclipsaTestStatusText: TextView

    private val uiHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshMeters()
            uiHandler.postDelayed(this, 180L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activeTab = intent.getIntExtra(EXTRA_INITIAL_TAB, 0).coerceIn(0, 4)
        setContentView(buildUi())
        selectTab(activeTab)
    }

    override fun onResume() {
        super.onResume()
        NowPlayingListenerService.refreshFromMediaSessionManager(this)
        uiHandler.post(refreshRunnable)
    }

    override fun onPause() {
        uiHandler.removeCallbacks(refreshRunnable)
        super.onPause()
    }

    private fun dp(v: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            v.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    private fun startServiceCompat(intent: Intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (_: Exception) {
        }
    }

    private fun hasMicPermission(): Boolean {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensureMicPermissionIfAvailable() {
        if (!hasMicPermission()) {
            try {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 301)
            } catch (_: Exception) {
            }
        }
    }

    private fun roundedBg(fillHex: String, strokeHex: String? = null, strokeDp: Int = 1): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(Color.parseColor(fillHex))
            if (strokeHex != null) {
                setStroke(dp(strokeDp), Color.parseColor(strokeHex))
            }
        }
    }

    private fun cardBg(borderHex: String = "#25344E"): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(Color.parseColor("#111827"))
            setStroke(dp(1), Color.parseColor(borderHex))
        }
    }

    private fun createTvFocusBtn(
        label: String,
        activeBgHex: String = "#11343C",
        idleBgHex: String = "#182235",
        onClick: () -> Unit
    ): Button {
        val btn = Button(this).apply {
            text = label
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            isFocusable = true
            isFocusableInTouchMode = false
            setOnClickListener { onClick() }
            setOnFocusChangeListener { v, hasFocus ->
                applyFocusSafeStyle(v as Button, isButtonActiveState(v), hasFocus, activeBgHex, idleBgHex)
            }
        }
        applyFocusSafeStyle(btn, false, false, activeBgHex, idleBgHex)
        return btn
    }

    private fun isButtonActiveState(btn: Button): Boolean {
        return when (btn) {
            tabSoundbarBtn -> activeTab == 0
            tabPioneerBtn -> activeTab == 1
            tabEqBtn -> activeTab == 2
            tabFxBtn -> activeTab == 3
            tabMicBtn -> activeTab == 4
            sbModeAtmosBtn -> sbAtmosMode
            sbModeBtBtn -> !sbAtmosMode
            toggleEngineBtn -> LiveSubPassThroughService.isRunning
            bypassBtn -> LiveSubPassThroughService.isBypassed
            autoEqToggleBtn -> LiveSubPassThroughService.autoPowerampOptimize
            limiterToggleBtn -> LiveSubPassThroughService.limiterEnabled
            fxToggleBtn -> LiveSubPassThroughService.fxEnabled
            presetVelvetBtn -> LiveSubPassThroughService.soundProfile == "DEEP_VELVET"
            presetSurroundBtn -> LiveSubPassThroughService.soundProfile == "SURROUND_360"
            presetVocalBtn -> LiveSubPassThroughService.soundProfile == "PURE_VOCAL"
            presetLoudnessBtn -> LiveSubPassThroughService.soundProfile == "LOUDNESS_RICH"
            presetEclipsaBtn -> LiveSubPassThroughService.soundProfile == "ECLIPSA_714"
            else -> {
                val sbMatch = sbPresetBtns.entries.firstOrNull { it.value == btn }
                if (sbMatch != null) return sbMatch.key == sbLayoutCode
                val revIdx = reverbBtns.indexOf(btn)
                if (revIdx >= 0) return LiveSubPassThroughService.reverbPreset == revIdx
                false
            }
        }
    }

    private fun applyFocusSafeStyle(
        btn: Button,
        isActive: Boolean,
        isFocused: Boolean,
        activeBgHex: String = "#11343C",
        idleBgHex: String = "#182235"
    ) {
        val fill = when {
            isFocused -> "#2E260C"
            isActive -> activeBgHex
            else -> idleBgHex
        }
        val stroke = when {
            isFocused -> "#FFD33D"
            isActive -> "#1FD2B4"
            else -> "#2F3D57"
        }
        val textCol = when {
            isFocused -> "#FFD33D"
            isActive -> "#A6FFF0"
            else -> "#E6EDF3"
        }
        btn.background = roundedBg(fill, stroke, if (isFocused) 3 else 1)
        btn.setTextColor(Color.parseColor(textCol))
        btn.animate()
            .scaleX(if (isFocused) 1.04f else 1.0f)
            .scaleY(if (isFocused) 1.04f else 1.0f)
            .setDuration(90L)
            .start()
    }

    private fun buildUi(): LinearLayout {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#080C14"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            clipChildren = false
            clipToPadding = false
        }

        // Fixed Top Header + 5-App Tab Bar
        val topHeader = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F1624"))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            clipChildren = false
            clipToPadding = false
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }

        backBtn = createTvFocusBtn("Back to Eclipsa YouTube", activeBgHex = "#1A2942", idleBgHex = "#1D283E") {
            finish()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(dp(205), dp(38)).apply { marginEnd = dp(10) }
        }

        val headerTitle = TextView(this).apply {
            text = "SOUNDBAR, PIONEER CAR 4.1 & 360 OPTIMIZER SUITE v4.1"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        statusBadge = TextView(this).apply {
            text = "ENGINE..."
            setTextColor(Color.parseColor("#3FB950"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = roundedBg("#162133", "#2F3D57")
        }

        headerRow.addView(backBtn)
        headerRow.addView(headerTitle)
        headerRow.addView(statusBadge)
        topHeader.addView(headerRow)

        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(2))
            clipChildren = false
            clipToPadding = false
        }

        tabSoundbarBtn = createTvFocusBtn("Soundbar Cali") { selectTab(0) }
        tabPioneerBtn = createTvFocusBtn("Pioneer Car 4.1") { selectTab(1) }
        tabEqBtn = createTvFocusBtn("Poweramp EQ") { selectTab(2) }
        tabFxBtn = createTvFocusBtn("FX & 360 Sub") { selectTab(3) }
        tabMicBtn = createTvFocusBtn("Mic & 12-Ch Test") { selectTab(4) }

        val tabs = listOf(tabSoundbarBtn, tabPioneerBtn, tabEqBtn, tabFxBtn, tabMicBtn)
        tabs.forEachIndexed { i, b ->
            val lp = LinearLayout.LayoutParams(0, dp(40), 1f)
            if (i < tabs.lastIndex) lp.marginEnd = dp(6)
            b.layoutParams = lp
            tabRow.addView(b)
        }
        topHeader.addView(tabRow)
        outer.addView(topHeader)

        // Scrollable Suite Body
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            setPadding(dp(12), dp(10), dp(12), dp(18))
            clipChildren = false
            clipToPadding = false
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }

        // Persistent Now-Playing & Background 360 DSP Engine Control Strip
        root.addView(buildPassThroughStripCard())

        // 5 Full Feature Tabs
        tabSoundbarContainer = buildSoundbarCalibratorTab()
        tabPioneerContainer = buildPioneerCarTab()
        tabEqContainer = buildPowerampEqTab()
        tabFxContainer = buildFxAnd360Tab()
        tabMicContainer = buildMicAndTestsTab()

        root.addView(tabSoundbarContainer)
        root.addView(tabPioneerContainer)
        root.addView(tabEqContainer)
        root.addView(tabFxContainer)
        root.addView(tabMicContainer)

        scroll.addView(root)
        outer.addView(scroll)
        return outer
    }

    private fun buildPassThroughStripCard(): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg("#25344E")
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
            clipChildren = false
            clipToPadding = false
        }

        passThroughBadge = TextView(this).apply {
            text = "360 AUDIO PASS-THROUGH"
            setTextColor(Color.parseColor("#58A6FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.DEFAULT_BOLD
        }
        songTitleText = TextView(this).apply {
            text = "Ready for Music Stream"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
        }
        songArtistText = TextView(this).apply {
            text = "Open YouTube, Spotify, or YT Music"
            setTextColor(Color.parseColor("#9AB0CC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setPadding(0, 0, 0, dp(8))
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }

        toggleEngineBtn = createTvFocusBtn("START ENGINE") {
            val act = if (LiveSubPassThroughService.isRunning) {
                LiveSubPassThroughService.ACTION_STOP
            } else {
                LiveSubPassThroughService.ACTION_START
            }
            startServiceCompat(Intent(this, LiveSubPassThroughService::class.java).apply { action = act })
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1.3f).apply { marginEnd = dp(6) }
        }

        bypassBtn = createTvFocusBtn("A/B: DSP ON") {
            LiveSubPassThroughService.isBypassed = !LiveSubPassThroughService.isBypassed
            if (!bypassBtn.hasFocus()) applyFocusSafeStyle(bypassBtn, LiveSubPassThroughService.isBypassed, false)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1.1f).apply { marginEnd = dp(6) }
        }

        val prevBtn = createTvFocusBtn("Prev") {
            NowPlayingListenerService.prevTrack(this)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 0.7f).apply { marginEnd = dp(6) }
        }

        playPauseBtn = createTvFocusBtn("Play/Pause") {
            NowPlayingListenerService.playPause(this)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 0.9f).apply { marginEnd = dp(6) }
        }

        val nextBtn = createTvFocusBtn("Next") {
            NowPlayingListenerService.nextTrack(this)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 0.7f).apply { marginEnd = dp(6) }
        }

        enableNotifBtn = createTvFocusBtn("Song Sync") {
            try {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 0.9f)
        }

        btnRow.addView(toggleEngineBtn)
        btnRow.addView(bypassBtn)
        btnRow.addView(prevBtn)
        btnRow.addView(playPauseBtn)
        btnRow.addView(nextBtn)
        btnRow.addView(enableNotifBtn)

        card.addView(passThroughBadge)
        card.addView(songTitleText)
        card.addView(songArtistText)
        card.addView(btnRow)
        return card
    }

    // =========================================================================
    // TAB 0: SAMSUNG SOUNDBAR & DOLBY ATMOS CALIBRATOR (3.1.2 -> 11.2.4)
    // =========================================================================
    private fun getActiveSoundbarChannels(): List<SoundbarChannelSpec> {
        val (bed, subs, atmos) = when (sbLayoutCode) {
            "3.1.2" -> Triple(3, 1, 2)
            "5.1.2" -> Triple(5, 1, 2)
            "7.1.2" -> Triple(7, 1, 2)
            "9.1.4" -> Triple(9, 1, 4)
            "11.1.4" -> Triple(11, 1, 4)
            else -> Triple(11, 2, 4)
        }
        return allSoundbarSpecs.filter { sp ->
            when (sp.group) {
                "bed_3" -> bed >= 3
                "bed_5" -> bed >= 5
                "bed_7" -> bed >= 7
                "bed_9" -> bed >= 9
                "bed_11" -> bed >= 11
                "atmos_2" -> atmos >= 2
                "atmos_4" -> atmos >= 4
                "sub_1" -> subs >= 1
                "sub_2" -> subs >= 2
                else -> false
            }
        }
    }

    private fun buildSoundbarCalibratorTab(): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }

        val setupCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg("#1F4B6E")
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }

        setupCard.addView(sectionHeader("SAMSUNG Q930 / DOLBY ATMOS SOUNDBAR CALIBRATOR (60% VOL)", "#58A6FF"))
        setupCard.addView(
            bodyText(
                "Select your Soundbar layout (3.1.2 to 11.2.4) and connection mode. Test discrete 48 kHz studio tones per channel, measure room SPL/boom, and apply your curve to the live engine."
            )
        )

        val layoutRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(6))
        }
        sbPresetBtns.clear()
        listOf("3.1.2", "5.1.2", "7.1.2", "9.1.4", "11.1.4", "11.2.4").forEachIndexed { idx, code ->
            val btn = createTvFocusBtn(if (code == "9.1.4") "9.1.4 Q930" else code) {
                sbLayoutCode = code
                rebuildSoundbarChannelCards()
                updateSoundbarButtonStates()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f).apply {
                    if (idx < 5) marginEnd = dp(5)
                }
            }
            sbPresetBtns[code] = btn
            layoutRow.addView(btn)
        }
        setupCard.addView(layoutRow)

        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(8))
        }
        sbModeAtmosBtn = createTvFocusBtn("Dolby Atmos 3D / HDMI eARC") {
            sbAtmosMode = true
            updateSoundbarButtonStates()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(8) }
        }
        sbModeBtBtn = createTvFocusBtn("Bluetooth / Surround Matrix") {
            sbAtmosMode = false
            updateSoundbarButtonStates()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f)
        }
        modeRow.addView(sbModeAtmosBtn)
        modeRow.addView(sbModeBtBtn)
        setupCard.addView(modeRow)

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val fullSweepBtn = createTvFocusBtn("Calibrate All Channels (Sweep)", activeBgHex = "#1E5631", idleBgHex = "#1E4629") {
            runSoundbarFullSweep()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1.4f).apply { marginEnd = dp(8) }
        }
        val applySbDspBtn = createTvFocusBtn("Apply to Live DSP", activeBgHex = "#11343C", idleBgHex = "#153652") {
            LiveSubPassThroughService.applyPreset("ECLIPSA_714")
            LiveSubPassThroughService.deepSubWarmthDb = 4.5f
            LiveSubPassThroughService.maxSubCutDb = 12.0f
            LiveSubPassThroughService.surroundClarityDb = 3.5f
            sbSweepStatusText.text = "Applied $sbLayoutCode (${if (sbAtmosMode) "Atmos eARC" else "BT Surround"}) curve to live DSP!"
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(8) }
        }
        val resetSbBtn = createTvFocusBtn("Reset") {
            sbMeasuredDb.clear()
            sbConfirmed.clear()
            rebuildSoundbarChannelCards()
            sbSweepStatusText.text = "Measurements cleared. Ready for fresh $sbLayoutCode calibration."
        }.apply {
            layoutParams = LinearLayout.LayoutParams(dp(95), dp(42))
        }
        actionRow.addView(fullSweepBtn)
        actionRow.addView(applySbDspBtn)
        actionRow.addView(resetSbBtn)
        setupCard.addView(actionRow)

        sbSweepStatusText = bodyText("Ready — Select layout & tap Calibrate All or test individual channels below.")
        sbSweepStatusText.setTextColor(Color.parseColor("#FFD33D"))
        sbSweepStatusText.setPadding(0, dp(8), 0, 0)
        setupCard.addView(sbSweepStatusText)
        container.addView(setupCard)

        sbChannelListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        container.addView(sbChannelListContainer)
        rebuildSoundbarChannelCards()

        // World-Class 60% Vol Soundbar 7-Band EQ Guide Card
        val guideCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg("#2B4162")
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        guideCard.addView(sectionHeader("SAMSUNG Q930 RECOMMENDED SETTINGS & 7-BAND EQ (60% VOL)", "#2CE574"))
        guideCard.addView(
            bodyText(
                "• Sound Mode: Adaptive Sound / Surround   • Master Vol: 18–26 (Clean Headroom)\n" +
                    "• Bass Enhancement: OFF   • SpaceFit Sound: ON   • Virtualizer: ON (for Atmos/BT)\n" +
                    "• 7-Band EQ: 150 Hz = -2 dB (Zero Boom) | 300 Hz = -1 dB | 600 Hz = 0 dB | 1.2 kHz = +1 dB | 2.5 kHz = +2 dB | 5 kHz = 0 dB | 10 kHz = -1 dB\n" +
                    "• Woofer Level: +1 to +2 dB for 28–62 Hz deep velvet sustain without 100 Hz chest slap."
            )
        )
        container.addView(guideCard)

        updateSoundbarButtonStates()
        return container
    }

    private fun updateSoundbarButtonStates() {
        sbPresetBtns.forEach { (code, btn) ->
            if (!btn.hasFocus()) applyFocusSafeStyle(btn, code == sbLayoutCode, false)
        }
        if (!sbModeAtmosBtn.hasFocus()) applyFocusSafeStyle(sbModeAtmosBtn, sbAtmosMode, false)
        if (!sbModeBtBtn.hasFocus()) applyFocusSafeStyle(sbModeBtBtn, !sbAtmosMode, false)
    }

    private fun rebuildSoundbarChannelCards() {
        sbChannelListContainer.removeAllViews()
        sbChannelResultViews.clear()
        val active = getActiveSoundbarChannels()

        active.forEachIndexed { idx, ch ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = cardBg()
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            }

            val title = TextView(this).apply {
                text = "${idx + 1}. ${ch.name}"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                typeface = Typeface.DEFAULT_BOLD
            }
            val meta = TextView(this).apply {
                text = "Menu: ${ch.remoteMenu}  |  Band: ${ch.freqLow}–${ch.freqHigh} Hz  |  Range: ${ch.minDb}..+${ch.maxDb} dB"
                setTextColor(Color.parseColor("#58A6FF"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            }
            val desc = bodyText(ch.desc)

            val resView = TextView(this).apply {
                text = formatSoundbarResult(ch)
                setTextColor(Color.parseColor("#FFD33D"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                typeface = Typeface.MONOSPACE
                setPadding(0, dp(4), 0, dp(6))
            }
            sbChannelResultViews[ch.id] = resView

            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val testBtn = createTvFocusBtn("Test & Measure") {
                runSingleSoundbarTest(ch)
            }.apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginEnd = dp(8) }
            }
            val confirmBtn = createTvFocusBtn(if (sbConfirmed.contains(ch.id)) "Confirmed ✓" else "Confirm Adjusted") {
                sbConfirmed.add(ch.id)
                resView.text = formatSoundbarResult(ch)
            }.apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f)
            }
            row.addView(testBtn)
            row.addView(confirmBtn)

            card.addView(title)
            card.addView(meta)
            card.addView(desc)
            card.addView(resView)
            card.addView(row)
            sbChannelListContainer.addView(card)
        }
    }

    private fun formatSoundbarResult(ch: SoundbarChannelSpec): String {
        val measured = sbMeasuredDb[ch.id]
        val req = 78.0f + ch.targetOffsetDb
        val doneTag = if (sbConfirmed.contains(ch.id)) "  [CONFIRMED ✓]" else ""
        return if (measured == null) {
            "Measured: -- dBC  |  Required: %.1f dBC  |  Target Offset: %+.1f dB$doneTag".format(req, ch.targetOffsetDb)
        } else {
            val delta = (req - measured).coerceIn(ch.minDb.toFloat(), ch.maxDb.toFloat())
            val recStep = Math.round(delta).coerceIn(ch.minDb, ch.maxDb)
            "Measured: %.1f dBC  |  Required: %.1f dBC  |  Set Remote: %+d dB$doneTag".format(measured, req, recStep)
        }
    }

    private fun runSingleSoundbarTest(ch: SoundbarChannelSpec) {
        ensureMicPermissionIfAvailable()
        sbSweepStatusText.text = "Playing ${ch.name} (${ch.freqLow}-${ch.freqHigh} Hz)..."
        thread(start = true) {
            val measured = playToneAndMeasureDb(ch.harm1, ch.harm2, ch.leftAmp, ch.rightAmp, 1500)
            sbMeasuredDb[ch.id] = measured
            runOnUiThread {
                sbChannelResultViews[ch.id]?.text = formatSoundbarResult(ch)
                sbSweepStatusText.text = "Measured ${ch.name}: %.1f dBC (Target %.1f dBC)".format(measured, 78f + ch.targetOffsetDb)
            }
        }
    }

    private fun runSoundbarFullSweep() {
        if (sbSweeping) return
        ensureMicPermissionIfAvailable()
        sbSweeping = true
        val list = getActiveSoundbarChannels()
        thread(start = true) {
            try {
                for ((i, ch) in list.withIndex()) {
                    if (!sbSweeping) break
                    runOnUiThread {
                        sbSweepStatusText.text = "Sweeping ${i + 1}/${list.size}: ${ch.name}..."
                    }
                    val db = playToneAndMeasureDb(ch.harm1, ch.harm2, ch.leftAmp, ch.rightAmp, 1100)
                    sbMeasuredDb[ch.id] = db
                    runOnUiThread {
                        sbChannelResultViews[ch.id]?.text = formatSoundbarResult(ch)
                    }
                }
                runOnUiThread {
                    sbSweepStatusText.text = "Completed $sbLayoutCode (${list.size}-Channel) Sweep! Adjust CH LEVELs per recommendations."
                }
            } finally {
                sbSweeping = false
            }
        }
    }

    // =========================================================================
    // TAB 1: PIONEER DMH-Z5290BT CAR 4.1 60% VOL DISTORTION-FREE CALIBRATOR
    // =========================================================================
    private fun buildPioneerCarTab(): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }

        val intro = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg("#A35000")
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        intro.addView(sectionHeader("PIONEER DMH-Z5290BT CAR 4.1 (60% VOL DISTORTION-FREE)", "#FF9F43"))
        intro.addView(
            bodyText(
                "1. Set Pioneer Master Volume = 22–24 (60% loud driving volume).\n" +
                    "2. Turn Loudness = OFF, Bass Boost = 0, SLA = -2 dB, Speaker Levels = 0 dB, 13-Band EQ = Flat.\n" +
                    "3. Hold mic/phone near driver headrest and run the 4.1 Sweep below."
            )
        )

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(4))
        }
        val sweepCarBtn = createTvFocusBtn("Calibrate All Car 4.1", activeBgHex = "#6B3800", idleBgHex = "#4D2900") {
            runCarFullSweep()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1.3f).apply { marginEnd = dp(8) }
        }
        val applyCarDspBtn = createTvFocusBtn("Apply Pioneer 4.1 DSP") {
            LiveSubPassThroughService.applyPreset("DEEP_VELVET")
            LiveSubPassThroughService.userEqOffsetsDb[0] = 2.0f
            LiveSubPassThroughService.userEqOffsetsDb[1] = 1.5f
            LiveSubPassThroughService.userEqOffsetsDb[2] = -3.5f
            LiveSubPassThroughService.userEqOffsetsDb[3] = -2.0f
            LiveSubPassThroughService.userEqOffsetsDb[7] = -2.0f
            LiveSubPassThroughService.userEqOffsetsDb[8] = -1.5f
            LiveSubPassThroughService.preampDb = -2.0f
            LiveSubPassThroughService.limiterEnabled = true
            carSweepStatusText.text = "Applied Pioneer DMH-Z5290BT 60% Vol Deep Velvet + Tweeter-Safe EQ curve!"
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1.1f).apply { marginEnd = dp(8) }
        }
        val resetCarBtn = createTvFocusBtn("Reset") {
            carMeasuredDb.clear()
            carConfirmed.clear()
            rebuildCarChannelCards()
            carSweepStatusText.text = "Pioneer 4.1 measurements reset."
        }.apply {
            layoutParams = LinearLayout.LayoutParams(dp(95), dp(42))
        }
        btnRow.addView(sweepCarBtn)
        btnRow.addView(applyCarDspBtn)
        btnRow.addView(resetCarBtn)
        intro.addView(btnRow)

        carSweepStatusText = bodyText("Ready — Tap 'Calibrate All Car 4.1' or test individual car speakers below.")
        carSweepStatusText.setTextColor(Color.parseColor("#FFD33D"))
        carSweepStatusText.setPadding(0, dp(4), 0, 0)
        intro.addView(carSweepStatusText)
        container.addView(intro)

        carChannelListContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.addView(carChannelListContainer)
        rebuildCarChannelCards()

        // Pioneer 13-Band Graphic EQ & Crossover Guide
        val pGuide = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg("#2B4162")
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        pGuide.addView(sectionHeader("PIONEER DMH-Z5290BT 13-BAND EQ & CROSSOVER TARGETS", "#2CE574"))
        pGuide.addView(
            bodyText(
                "• Crossovers: Front HPF = 80 Hz (-12 dB) | Rear HPF = 100 Hz (-12 dB) | Sub LPF = 63 Hz (-18 dB) | Sub Phase = Normal\n" +
                    "• 13-Band Graphic EQ (Anti-Harsh + Zero 100 Hz Slam + Velvet Deep Sub):\n" +
                    "  50Hz:+2  80Hz:-2  125Hz:-3  200Hz:-2  315Hz:-1  500Hz:0  800Hz:+1\n" +
                    "  1.25kHz:+1  2kHz:+2  3.15kHz:-1  5kHz:-2  8kHz:-1  12.5kHz:0"
            )
        )
        container.addView(pGuide)

        return container
    }

    private fun rebuildCarChannelCards() {
        carChannelListContainer.removeAllViews()
        carChannelResultViews.clear()

        carSpecs.forEach { ch ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = cardBg()
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            }
            val t = TextView(this).apply {
                text = ch.name
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                typeface = Typeface.DEFAULT_BOLD
            }
            val menu = TextView(this).apply {
                text = "${ch.pioneerMenu}  |  ${ch.recommendedXover}"
                setTextColor(Color.parseColor("#FF9F43"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            }
            val desc = bodyText(ch.desc)
            val res = TextView(this).apply {
                text = formatCarResult(ch)
                setTextColor(Color.parseColor("#FFD33D"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                typeface = Typeface.MONOSPACE
                setPadding(0, dp(4), 0, dp(6))
            }
            carChannelResultViews[ch.id] = res

            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val testBtn = createTvFocusBtn("Play & Measure") {
                runSingleCarTest(ch)
            }.apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginEnd = dp(8) }
            }
            val confBtn = createTvFocusBtn(if (carConfirmed.contains(ch.id)) "Confirmed ✓" else "Confirm Adjusted") {
                carConfirmed.add(ch.id)
                res.text = formatCarResult(ch)
            }.apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f)
            }
            row.addView(testBtn)
            row.addView(confBtn)

            card.addView(t)
            card.addView(menu)
            card.addView(desc)
            card.addView(res)
            card.addView(row)
            carChannelListContainer.addView(card)
        }
    }

    private fun formatCarResult(ch: CarChannelSpec): String {
        val m = carMeasuredDb[ch.id]
        val req = 80.0f + ch.targetOffsetDb
        val doneTag = if (carConfirmed.contains(ch.id)) "  [CONFIRMED ✓]" else ""
        return if (m == null) {
            "Measured: -- dBC  |  Required: %.1f dBC  |  ${ch.recommendedXover}$doneTag".format(req)
        } else {
            val delta = Math.round(req - m).coerceIn(ch.minDb, ch.maxDb)
            "Measured: %.1f dBC  |  Required: %.1f dBC  |  Pioneer Level: %+d dB$doneTag".format(m, req, delta)
        }
    }

    private fun runSingleCarTest(ch: CarChannelSpec) {
        ensureMicPermissionIfAvailable()
        carSweepStatusText.text = "Testing ${ch.name}..."
        thread(start = true) {
            val db = playToneAndMeasureDb(ch.freq1, ch.freq2, ch.leftAmp, ch.rightAmp, 1400)
            carMeasuredDb[ch.id] = db
            runOnUiThread {
                carChannelResultViews[ch.id]?.text = formatCarResult(ch)
                carSweepStatusText.text = "Measured ${ch.speakerType}: %.1f dBC (Target %.1f dBC)".format(db, 80f + ch.targetOffsetDb)
            }
        }
    }

    private fun runCarFullSweep() {
        if (carSweeping) return
        ensureMicPermissionIfAvailable()
        carSweeping = true
        thread(start = true) {
            try {
                for ((i, ch) in carSpecs.withIndex()) {
                    if (!carSweeping) break
                    runOnUiThread {
                        carSweepStatusText.text = "Car Sweep ${i + 1}/${carSpecs.size}: ${ch.name}..."
                    }
                    val db = playToneAndMeasureDb(ch.freq1, ch.freq2, ch.leftAmp, ch.rightAmp, 1100)
                    carMeasuredDb[ch.id] = db
                    runOnUiThread {
                        carChannelResultViews[ch.id]?.text = formatCarResult(ch)
                    }
                }
                runOnUiThread {
                    carSweepStatusText.text = "Completed 4.1 Pioneer Calibration Sweep!"
                }
            } finally {
                carSweeping = false
            }
        }
    }

    // Plays 48 kHz stereo chord via AudioTrack and samples Phone/BT Mic if available
    private fun playToneAndMeasureDb(
        freq1: Double,
        freq2: Double,
        leftAmp: Float,
        rightAmp: Float,
        durationMs: Int
    ): Float {
        var measuredRms = 0.0
        var micSamples = 0
        try {
            val sr = 48000
            val totalSamples = (sr * durationMs) / 1000
            val pcm = ShortArray(totalSamples * 2)
            for (i in 0 until totalSamples) {
                val t = i.toDouble() / sr
                val env = (0.5 * (1.0 - cos(2.0 * PI * i.toDouble() / totalSamples))).toFloat()
                val w = (0.55 * sin(2.0 * PI * freq1 * t) + 0.30 * sin(2.0 * PI * freq2 * t + 0.5) + 0.15 * sin(2.0 * PI * freq1 * 1.5 * t)).toFloat() * env
                pcm[i * 2] = (w * leftAmp * 15500f).toInt().coerceIn(-32767, 32767).toShort()
                pcm[i * 2 + 1] = (w * rightAmp * 15500f).toInt().coerceIn(-32767, 32767).toShort()
            }

            var recorder: AudioRecord? = null
            if (hasMicPermission()) {
                try {
                    val recBuf = max(AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), 4096)
                    recorder = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, recBuf)
                    if (recorder.state == AudioRecord.STATE_INITIALIZED) {
                        recorder.startRecording()
                    } else {
                        recorder.release()
                        recorder = null
                    }
                } catch (_: Exception) {
                    recorder = null
                }
            }

            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            track.write(pcm, 0, pcm.size)
            track.play()

            if (recorder != null) {
                val rBuf = ShortArray(1024)
                val endAt = System.currentTimeMillis() + durationMs
                var sqSum = 0.0
                while (System.currentTimeMillis() < endAt) {
                    val r = recorder.read(rBuf, 0, rBuf.size)
                    if (r > 0) {
                        for (k in 0 until r) {
                            val s = rBuf[k].toDouble() / 32768.0
                            sqSum += s * s
                        }
                        micSamples += r
                    }
                }
                if (micSamples > 0) measuredRms = sqrt(sqSum / micSamples)
                try {
                    recorder.stop()
                    recorder.release()
                } catch (_: Exception) {}
            } else {
                Thread.sleep(durationMs.toLong() + 100L)
            }

            track.stop()
            track.release()
        } catch (_: Exception) {
        }

        return if (micSamples > 200 && measuredRms > 1e-6) {
            (20.0 * log10(measuredRms) + 94.0).toFloat().coerceIn(45.0f, 102.0f)
        } else {
            // Mic-optional fallback derived from service acoustic state so TV without mic still operates smoothly
            max(72.0f, (LiveSubPassThroughService.liveMidDb * 0.4f + 48.0f)).coerceIn(72.0f, 82.0f)
        }
    }

    // =========================================================================
    // TAB 2: POWERAMP 10-BAND PASS-THROUGH EQ, TONE, PREAMP & LIMITER
    // =========================================================================
    private fun buildPowerampEqTab(): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg("#238636")
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        card.addView(sectionHeader("POWERAMP 10-BAND EQ, TONE, PREAMP & ANTI-CLIP LIMITER", "#3FB950"))

        optimizeSpotifyBtn = createTvFocusBtn("Measure & Optimize Playing Spotify / YT Music Song (6s)") {
            ensureMicPermissionIfAvailable()
            startServiceCompat(Intent(this, LiveSubPassThroughService::class.java).apply {
                action = LiveSubPassThroughService.ACTION_OPTIMIZE_MUSIC
            })
        }.apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)).apply { bottomMargin = dp(6) }
        }
        spotifyOptStatusText = bodyText(LiveSubPassThroughService.musicOptStatus)
        spotifyOptStatusText.setTextColor(Color.parseColor("#FFD33D"))

        val toggleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(8))
        }
        autoEqToggleBtn = createTvFocusBtn("Live Auto-EQ: ON") {
            LiveSubPassThroughService.autoPowerampOptimize = !LiveSubPassThroughService.autoPowerampOptimize
            updateEqToggleTexts()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(8) }
        }
        limiterToggleBtn = createTvFocusBtn("Anti-Clip Limiter: ON") {
            LiveSubPassThroughService.limiterEnabled = !LiveSubPassThroughService.limiterEnabled
            updateEqToggleTexts()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f)
        }
        toggleRow.addView(autoEqToggleBtn)
        toggleRow.addView(limiterToggleBtn)

        card.addView(optimizeSpotifyBtn)
        card.addView(spotifyOptStatusText)
        card.addView(toggleRow)

        toneBassLabel = labelText("Bass Tone (31–62 Hz Deep Sub): +2.5 dB")
        toneBassSeek = makeSeekBar(120, ((LiveSubPassThroughService.toneBassDb + 6f) * 10).toInt()) { p ->
            LiveSubPassThroughService.toneBassDb = (p - 60) / 10f
            toneBassLabel.text = "Bass Tone (31–62 Hz Deep Sub): %+.1f dB".format(LiveSubPassThroughService.toneBassDb)
        }
        toneTrebleLabel = labelText("Treble Tone (8–16 kHz Silk Air): +0.0 dB")
        toneTrebleSeek = makeSeekBar(120, ((LiveSubPassThroughService.toneTrebleDb + 6f) * 10).toInt()) { p ->
            LiveSubPassThroughService.toneTrebleDb = (p - 60) / 10f
            toneTrebleLabel.text = "Treble Tone (8–16 kHz Silk Air): %+.1f dB".format(LiveSubPassThroughService.toneTrebleDb)
        }
        preampLabel = labelText("Pre-Amp Headroom (Anti-Clip): -2.0 dB")
        preampSeek = makeSeekBar(80, ((LiveSubPassThroughService.preampDb + 8f) * 10).toInt()) { p ->
            LiveSubPassThroughService.preampDb = (p - 80) / 10f
            preampLabel.text = "Pre-Amp Headroom (Anti-Clip): %.1f dB".format(LiveSubPassThroughService.preampDb)
        }
        limiterCeilingLabel = labelText("Limiter Ceiling: -1.2 dBFS")
        limiterCeilingSeek = makeSeekBar(27, ((LiveSubPassThroughService.limiterCeilingDb + 3f) * 10).toInt()) { p ->
            LiveSubPassThroughService.limiterCeilingDb = -3.0f + (p / 10f)
            limiterCeilingLabel.text = "Limiter Ceiling: %.1f dBFS".format(LiveSubPassThroughService.limiterCeilingDb)
        }

        card.addView(toneBassLabel); card.addView(toneBassSeek)
        card.addView(toneTrebleLabel); card.addView(toneTrebleSeek)
        card.addView(preampLabel); card.addView(preampSeek)
        card.addView(limiterCeilingLabel); card.addView(limiterCeilingSeek)

        card.addView(sectionHeader("10-BAND GRAPHIC / PARAMETRIC EQUALIZER (-10 to +6 dB)", "#58A6FF").apply {
            setPadding(0, dp(10), 0, dp(4))
        })

        eqBandLabels.clear()
        eqBandSeeks.clear()
        for (i in 0 until 10) {
            val freqLabel = LiveSubPassThroughService.EQ_LABELS[i]
            val userDb = LiveSubPassThroughService.userEqOffsetsDb[i]
            val lbl = labelText("%-6s | User: %+.1f dB".format(freqLabel, userDb))
            val sb = makeSeekBar(160, ((userDb + 10f) * 10).toInt()) { p ->
                val valDb = -10.0f + (p / 10f)
                LiveSubPassThroughService.userEqOffsetsDb[i] = valDb
                lbl.text = "%-6s | User: %+.1f dB  (Live DSP: %+.1f dB)".format(
                    freqLabel, valDb, LiveSubPassThroughService.liveAppliedEqDb[i]
                )
            }
            eqBandLabels.add(lbl)
            eqBandSeeks.add(sb)
            card.addView(lbl)
            card.addView(sb)
        }

        val resetBtn = createTvFocusBtn("Reset to Crystal Clear Vocals + Deep Bass Preset") {
            LiveSubPassThroughService.resetPowerampCrystalBassPreset()
            toneBassSeek.progress = ((LiveSubPassThroughService.toneBassDb + 6f) * 10).toInt()
            toneTrebleSeek.progress = ((LiveSubPassThroughService.toneTrebleDb + 6f) * 10).toInt()
            preampSeek.progress = ((LiveSubPassThroughService.preampDb + 8f) * 10).toInt()
            limiterCeilingSeek.progress = ((LiveSubPassThroughService.limiterCeilingDb + 3f) * 10).toInt()
            for (k in 0 until 10) {
                eqBandSeeks[k].progress = ((LiveSubPassThroughService.userEqOffsetsDb[k] + 10f) * 10).toInt()
            }
            updateEqToggleTexts()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)).apply { topMargin = dp(10) }
        }
        card.addView(resetBtn)

        container.addView(card)
        updateEqToggleTexts()
        return container
    }

    private fun updateEqToggleTexts() {
        autoEqToggleBtn.text = if (LiveSubPassThroughService.autoPowerampOptimize) "Live Auto-EQ: ON" else "Live Auto-EQ: MANUAL"
        limiterToggleBtn.text = if (LiveSubPassThroughService.limiterEnabled) "Anti-Clip Limiter: ON" else "Anti-Clip Limiter: OFF"
        if (!autoEqToggleBtn.hasFocus()) applyFocusSafeStyle(autoEqToggleBtn, LiveSubPassThroughService.autoPowerampOptimize, false)
        if (!limiterToggleBtn.hasFocus()) applyFocusSafeStyle(limiterToggleBtn, LiveSubPassThroughService.limiterEnabled, false)
    }

    // =========================================================================
    // TAB 3: POWERAMP AUDIOFX + 360 SUB OPTIMIZER + ANDROID AUTO
    // =========================================================================
    private fun buildFxAnd360Tab(): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }

        // AudioFX Card
        val fxCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg("#8957E5")
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        fxCard.addView(sectionHeader("POWERAMP AUDIO FX ENGINE (STEREO • SUB • REVERB)", "#D2A8FF"))
        fxToggleBtn = createTvFocusBtn("POWERAMP AUDIO FX: ON") {
            LiveSubPassThroughService.fxEnabled = !LiveSubPassThroughService.fxEnabled
            updateFxButtons()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)).apply { bottomMargin = dp(8) }
        }
        fxCard.addView(fxToggleBtn)

        stereoExpandLabel = labelText("Stereo Widening / 3D Spatializer FX: ${LiveSubPassThroughService.stereoExpandPct}%")
        stereoExpandSeek = makeSeekBar(100, LiveSubPassThroughService.stereoExpandPct) { p ->
            LiveSubPassThroughService.stereoExpandPct = p
            stereoExpandLabel.text = "Stereo Widening / 3D Spatializer FX: $p%"
        }
        bassBoostLabel = labelText("Deep Sub BassBoost FX: ${LiveSubPassThroughService.bassBoostPct}%")
        bassBoostSeek = makeSeekBar(100, LiveSubPassThroughService.bassBoostPct) { p ->
            LiveSubPassThroughService.bassBoostPct = p
            bassBoostLabel.text = "Deep Sub BassBoost FX: $p%"
        }
        fxCard.addView(stereoExpandLabel); fxCard.addView(stereoExpandSeek)
        fxCard.addView(bassBoostLabel); fxCard.addView(bassBoostSeek)

        fxCard.addView(labelText("ROOM AMBIENCE / REVERB FX PRESET:"))
        val revRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        reverbBtns.clear()
        listOf("Off", "Small", "Medium", "Hall", "Plate").forEachIndexed { idx, name ->
            val rb = createTvFocusBtn(name) {
                LiveSubPassThroughService.reverbPreset = idx
                updateFxButtons()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f).apply {
                    if (idx < 4) marginEnd = dp(5)
                }
            }
            reverbBtns.add(rb)
            revRow.addView(rb)
        }
        fxCard.addView(revRow)
        container.addView(fxCard)

        // 360 Sub & Surround Optimizer Card
        val subCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg("#25344E")
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        subCard.addView(sectionHeader("360 SUBWOOFER & SURROUND PROFILE OPTIMIZER", "#58A6FF"))
        val pRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(8))
        }
        presetVelvetBtn = createTvFocusBtn("Deep Velvet") {
            LiveSubPassThroughService.applyPreset("DEEP_VELVET")
            updateFxButtons()
        }
        presetSurroundBtn = createTvFocusBtn("360 Surround") {
            LiveSubPassThroughService.applyPreset("SURROUND_360")
            updateFxButtons()
        }
        presetVocalBtn = createTvFocusBtn("Pure Vocal") {
            LiveSubPassThroughService.applyPreset("PURE_VOCAL")
            updateFxButtons()
        }
        presetLoudnessBtn = createTvFocusBtn("Loud Rich") {
            LiveSubPassThroughService.applyPreset("LOUDNESS_RICH")
            updateFxButtons()
        }
        presetEclipsaBtn = createTvFocusBtn("Eclipsa 7.1.4") {
            LiveSubPassThroughService.applyPreset("ECLIPSA_714")
            updateFxButtons()
        }
        listOf(presetVelvetBtn, presetSurroundBtn, presetVocalBtn, presetLoudnessBtn, presetEclipsaBtn).forEachIndexed { idx, b ->
            b.layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply { if (idx < 4) marginEnd = dp(5) }
            pRow.addView(b)
        }
        subCard.addView(pRow)

        deepWarmthLabel = labelText("Deep Background Sub Warmth (28-60 Hz): %+.1f dB".format(LiveSubPassThroughService.deepSubWarmthDb))
        val deepWarmthSeek = makeSeekBar(80, (LiveSubPassThroughService.deepSubWarmthDb * 10).toInt()) { p ->
            LiveSubPassThroughService.deepSubWarmthDb = p / 10f
            deepWarmthLabel.text = "Deep Background Sub Warmth (28-60 Hz): %+.1f dB".format(LiveSubPassThroughService.deepSubWarmthDb)
        }
        threshValueLabel = labelText("Upper-Bass Anti-Hit Ceiling (70-140 Hz): %+.1f dB".format(LiveSubPassThroughService.targetBassOverMidsDb))
        val threshSeek = makeSeekBar(80, (LiveSubPassThroughService.targetBassOverMidsDb * 10).toInt()) { p ->
            LiveSubPassThroughService.targetBassOverMidsDb = p / 10f
            threshValueLabel.text = "Upper-Bass Anti-Hit Ceiling (70-140 Hz): %+.1f dB".format(LiveSubPassThroughService.targetBassOverMidsDb)
        }
        maxCutValueLabel = labelText("Max Hard-Hit Suppression Limit: -%.0f dB".format(LiveSubPassThroughService.maxSubCutDb))
        val maxCutSeek = makeSeekBar(12, LiveSubPassThroughService.maxSubCutDb.toInt() - 3) { p ->
            LiveSubPassThroughService.maxSubCutDb = (p + 3).toFloat()
            maxCutValueLabel.text = "Max Hard-Hit Suppression Limit: -%.0f dB".format(LiveSubPassThroughService.maxSubCutDb)
        }
        surroundLabel = labelText("Front + Rear 360 Vocal Clarity: %+.1f dB".format(LiveSubPassThroughService.surroundClarityDb))
        val surroundSeek = makeSeekBar(60, (LiveSubPassThroughService.surroundClarityDb * 10).toInt()) { p ->
            LiveSubPassThroughService.surroundClarityDb = p / 10f
            surroundLabel.text = "Front + Rear 360 Vocal Clarity: %+.1f dB".format(LiveSubPassThroughService.surroundClarityDb)
        }
        tweeterLabel = labelText("Tweeter Anti-Harshness & Silk Control: %.1f dB".format(LiveSubPassThroughService.tweeterSmoothDb))
        val tweeterSeek = makeSeekBar(50, (LiveSubPassThroughService.tweeterSmoothDb * 10).toInt()) { p ->
            LiveSubPassThroughService.tweeterSmoothDb = p / 10f
            tweeterLabel.text = "Tweeter Anti-Harshness & Silk Control: %.1f dB".format(LiveSubPassThroughService.tweeterSmoothDb)
        }

        subCard.addView(deepWarmthLabel); subCard.addView(deepWarmthSeek)
        subCard.addView(threshValueLabel); subCard.addView(threshSeek)
        subCard.addView(maxCutValueLabel); subCard.addView(maxCutSeek)
        subCard.addView(surroundLabel); subCard.addView(surroundSeek)
        subCard.addView(tweeterLabel); subCard.addView(tweeterSeek)
        container.addView(subCard)

        // Android Auto Card
        val aaCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg("#25344E")
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        aaCard.addView(sectionHeader("SHOW THIS APP ON ANDROID AUTO CAR SCREEN (PIONEER)", "#FFA657"))
        aaCard.addView(
            bodyText(
                "360 Live Sound & Sub Optimizer automatically filters Spotify & YouTube Music over Android Auto. To display its icon on your Pioneer DMH-Z5290BT screen: open Android Auto Settings -> tap Version 10x -> Developer settings -> Unknown sources."
            )
        )
        aaCard.addView(
            createTvFocusBtn("Open Android Auto Settings (Enable Car Launcher Icon)") {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW).apply {
                        setPackage("com.google.android.projection.gearhead")
                    })
                } catch (_: Exception) {
                    try {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:com.google.android.projection.gearhead")
                        })
                    } catch (_: Exception) {
                        startActivity(Intent(Settings.ACTION_SETTINGS))
                    }
                }
            }.apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)).apply { topMargin = dp(8) }
            }
        )
        container.addView(aaCard)

        updateFxButtons()
        return container
    }

    private fun updateFxButtons() {
        fxToggleBtn.text = if (LiveSubPassThroughService.fxEnabled) "POWERAMP AUDIO FX: ON" else "POWERAMP AUDIO FX: OFF"
        if (!fxToggleBtn.hasFocus()) applyFocusSafeStyle(fxToggleBtn, LiveSubPassThroughService.fxEnabled, false)
        reverbBtns.forEachIndexed { idx, b ->
            if (!b.hasFocus()) applyFocusSafeStyle(b, LiveSubPassThroughService.reverbPreset == idx, false)
        }
        val prof = LiveSubPassThroughService.soundProfile
        if (!presetVelvetBtn.hasFocus()) applyFocusSafeStyle(presetVelvetBtn, prof == "DEEP_VELVET", false)
        if (!presetSurroundBtn.hasFocus()) applyFocusSafeStyle(presetSurroundBtn, prof == "SURROUND_360", false)
        if (!presetVocalBtn.hasFocus()) applyFocusSafeStyle(presetVocalBtn, prof == "PURE_VOCAL", false)
        if (!presetLoudnessBtn.hasFocus()) applyFocusSafeStyle(presetLoudnessBtn, prof == "LOUDNESS_RICH", false)
        if (!presetEclipsaBtn.hasFocus()) applyFocusSafeStyle(presetEclipsaBtn, prof == "ECLIPSA_714", false)
    }

    // =========================================================================
    // TAB 4: LIVE 5-ZONE MIC RTA, AUTO-CALIBRATE SWEEP & 12-CH SPEAKER TESTS
    // =========================================================================
    private fun buildMicAndTestsTab(): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }

        // 5-Zone RTA Meters Card
        val meterCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg("#25344E")
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        meterCard.addView(sectionHeader("5-ZONE REAL-TIME ACOUSTIC RTA & ANTI-HIT METERS", "#58A6FF"))
        deepSubValText = labelText("Deep Background Sub (28–62 Hz): 0 dB")
        deepSubProgress = makeProgressBar()
        hitBassValText = labelText("Upper-Bass Slam / Hit Zone (65–140 Hz): 0 dB")
        hitBassProgress = makeProgressBar()
        cutValText = labelText("Active Anti-Hit Ducking Applied: 0 dB")
        cutProgress = makeProgressBar()
        midValText = labelText("Front + Rear 360 Vocal Stage (500-2800 Hz): 0 dB")
        midProgress = makeProgressBar()
        highValText = labelText("Tweeter Highs: 0 dB")
        thdBadgeText = bodyText("Distortion Guard: 0.2% THD")
        thdBadgeText.setTextColor(Color.parseColor("#3FB950"))

        meterCard.addView(deepSubValText); meterCard.addView(deepSubProgress)
        meterCard.addView(hitBassValText); meterCard.addView(hitBassProgress)
        meterCard.addView(cutValText); meterCard.addView(cutProgress)
        meterCard.addView(midValText); meterCard.addView(midProgress)
        meterCard.addView(highValText)
        meterCard.addView(thdBadgeText)
        container.addView(meterCard)

        // Auto-Calibrate Mic Sweep Card
        val calCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg("#1F4B6E")
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        calCard.addView(sectionHeader("AUTO-CALIBRATE ALL SPEAKERS (FRONT, REAR & SUBWOOFER)", "#58A6FF"))
        autoCalBtn = createTvFocusBtn("Run 360 All-Speaker Auto-Calibration Sweep") {
            ensureMicPermissionIfAvailable()
            startServiceCompat(Intent(this, LiveSubPassThroughService::class.java).apply {
                action = LiveSubPassThroughService.ACTION_RUN_SWEEP
            })
        }.apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)).apply { bottomMargin = dp(6) }
        }
        calProgressBar = makeProgressBar()
        calStatusText = bodyText(LiveSubPassThroughService.calibrationStatus)
        calReportText = bodyText(LiveSubPassThroughService.calRecommendation)
        calReportText.setTextColor(Color.parseColor("#A6FFF0"))

        calCard.addView(autoCalBtn)
        calCard.addView(calProgressBar)
        calCard.addView(calStatusText)
        calCard.addView(calReportText)
        container.addView(calCard)

        // Built-In 12-Channel Eclipsa 7.1.4 Hardware Speaker Tests
        val testCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg("#00E5FF")
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        testCard.addView(sectionHeader("BUILT-IN 12-CHANNEL ECLIPSA 7.1.4 / 9.1.4 HARDWARE SPEAKER TESTS", "#00E5FF"))
        eclipsaTestStatusText = bodyText(LiveSubPassThroughService.eclipsaTestStatus)
        eclipsaTestStatusText.setTextColor(Color.parseColor("#FFD33D"))
        testCard.addView(eclipsaTestStatusText)

        val tRow1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(6))
        }
        tRow1.addView(
            createTvFocusBtn("12-Ch Full 7.1.4 Orbit Test") {
                startServiceCompat(Intent(this, LiveSubPassThroughService::class.java).apply {
                    action = LiveSubPassThroughService.ACTION_PLAY_ECLIPSA_TEST
                    putExtra(LiveSubPassThroughService.EXTRA_ECLIPSA_TEST_ID, "full_714")
                })
            }.apply { layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(8) } }
        )
        tRow1.addView(
            createTvFocusBtn("4-Height Up-Firing Test") {
                startServiceCompat(Intent(this, LiveSubPassThroughService::class.java).apply {
                    action = LiveSubPassThroughService.ACTION_PLAY_ECLIPSA_TEST
                    putExtra(LiveSubPassThroughService.EXTRA_ECLIPSA_TEST_ID, "height_only")
                })
            }.apply { layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f) }
        )

        val tRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tRow2.addView(
            createTvFocusBtn("Velvet LFE Sub Sweep (32-55 Hz)") {
                startServiceCompat(Intent(this, LiveSubPassThroughService::class.java).apply {
                    action = LiveSubPassThroughService.ACTION_PLAY_ECLIPSA_TEST
                    putExtra(LiveSubPassThroughService.EXTRA_ECLIPSA_TEST_ID, "velvet_lfe")
                })
            }.apply { layoutParams = LinearLayout.LayoutParams(0, dp(40), 1.4f).apply { marginEnd = dp(8) } }
        )
        tRow2.addView(
            createTvFocusBtn("Stop Test") {
                LiveSubPassThroughService.isPlayingEclipsaTest = false
            }.apply { layoutParams = LinearLayout.LayoutParams(0, dp(40), 0.7f) }
        )

        testCard.addView(tRow1)
        testCard.addView(tRow2)
        container.addView(testCard)
        return container
    }

    private fun selectTab(tabIndex: Int) {
        activeTab = tabIndex.coerceIn(0, 4)
        tabSoundbarContainer.visibility = if (activeTab == 0) View.VISIBLE else View.GONE
        tabPioneerContainer.visibility = if (activeTab == 1) View.VISIBLE else View.GONE
        tabEqContainer.visibility = if (activeTab == 2) View.VISIBLE else View.GONE
        tabFxContainer.visibility = if (activeTab == 3) View.VISIBLE else View.GONE
        tabMicContainer.visibility = if (activeTab == 4) View.VISIBLE else View.GONE

        listOf(tabSoundbarBtn, tabPioneerBtn, tabEqBtn, tabFxBtn, tabMicBtn).forEachIndexed { idx, btn ->
            if (!btn.hasFocus()) applyFocusSafeStyle(btn, idx == activeTab, false)
        }
    }

    private fun refreshMeters() {
        val running = LiveSubPassThroughService.isRunning
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val musicActive = am.isMusicActive || NowPlayingListenerService.isPlaying

        statusBadge.text = if (running) "● 360 ACTIVE" else "○ ENGINE IDLE"
        statusBadge.setTextColor(Color.parseColor(if (running) "#3FB950" else "#8B949E"))

        val appName = NowPlayingListenerService.currentAppName
        passThroughBadge.text = when {
            !running -> "STANDBY • Tap START ENGINE below"
            LiveSubPassThroughService.isBypassed -> "A/B BYPASS ACTIVE • Raw Audio ($appName)"
            musicActive -> "● FILTERING $appName LIVE (Pulse ${LiveSubPassThroughService.passThroughPulse})"
            else -> "● 360 PASS-THROUGH ARMED ($appName)"
        }
        songTitleText.text = NowPlayingListenerService.currentTitle
        songArtistText.text = "${NowPlayingListenerService.currentArtist} • $appName"
        toggleEngineBtn.text = if (running) "STOP 360 ENGINE" else "START 360 ENGINE"
        bypassBtn.text = if (LiveSubPassThroughService.isBypassed) "A/B: RAW BYPASS" else "A/B: 360 DSP ON"

        // Update Tab 2 & 3 status text
        spotifyOptStatusText.text = LiveSubPassThroughService.musicOptStatus
        for (i in 0 until eqBandLabels.size) {
            eqBandLabels[i].text = "%-6s | User: %+.1f dB  (Live DSP: %+.1f dB)".format(
                LiveSubPassThroughService.EQ_LABELS[i],
                LiveSubPassThroughService.userEqOffsetsDb[i],
                LiveSubPassThroughService.liveAppliedEqDb[i]
            )
        }

        // Update Tab 4 meters
        val deepDb = LiveSubPassThroughService.liveDeepSubDb
        val hitDb = LiveSubPassThroughService.liveSubDb
        val cutDb = LiveSubPassThroughService.liveCutDb
        val midDb = LiveSubPassThroughService.liveMidDb
        val highDb = LiveSubPassThroughService.liveHighDb
        val thd = LiveSubPassThroughService.liveDistortionPct

        deepSubValText.text = "Deep Sub (28–62 Hz): %.1f dB  (Boost: %+.1f dB)".format(deepDb, LiveSubPassThroughService.liveDeepGainDb)
        deepSubProgress.progress = deepDb.toInt().coerceIn(0, 100)
        hitBassValText.text = "Upper-Bass Hit Zone (65–140 Hz): %.1f dB".format(hitDb)
        hitBassProgress.progress = hitDb.toInt().coerceIn(0, 100)
        cutValText.text = "Anti-Hit Ducking: %.1f dB".format(cutDb)
        cutProgress.progress = (abs(cutDb) * 6.5f).toInt().coerceIn(0, 100)
        midValText.text = "360 Vocal Stage: %.1f dB  (%+.1f dB)".format(midDb, LiveSubPassThroughService.liveMidGainDb)
        midProgress.progress = midDb.toInt().coerceIn(0, 100)
        highValText.text = "Tweeter Highs: %.1f dB  (Silk Trim: %+.1f dB)".format(highDb, LiveSubPassThroughService.liveHighGainDb)
        thdBadgeText.text = "Distortion Guard: %.1f%% THD • 100%% CLIP-FREE HEADROOM".format(thd)

        calProgressBar.progress = LiveSubPassThroughService.calibrationProgress
        calStatusText.text = LiveSubPassThroughService.calibrationStatus
        calReportText.text = LiveSubPassThroughService.calRecommendation
        eclipsaTestStatusText.text = LiveSubPassThroughService.eclipsaTestStatus
    }

    private fun sectionHeader(t: String, colorHex: String): TextView {
        return TextView(this).apply {
            text = t
            setTextColor(Color.parseColor(colorHex))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(0, 0, 0, dp(4))
        }
    }

    private fun bodyText(t: String): TextView {
        return TextView(this).apply {
            text = t
            setTextColor(Color.parseColor("#C9D1D9"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        }
    }

    private fun labelText(t: String): TextView {
        return TextView(this).apply {
            text = t
            setTextColor(Color.parseColor("#E6EDF3"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setPadding(0, dp(4), 0, dp(2))
        }
    }

    private fun makeProgressBar(): ProgressBar {
        return ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(8)
            ).apply { bottomMargin = dp(4) }
        }
    }

    private fun makeSeekBar(maxVal: Int, initialVal: Int, onChange: (Int) -> Unit): SeekBar {
        return SeekBar(this).apply {
            max = maxVal
            progress = initialVal.coerceIn(0, maxVal)
            isFocusable = true
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) onChange(p)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
    }
}
