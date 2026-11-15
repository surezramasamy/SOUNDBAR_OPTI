package com.soundbaropti.livesub

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.app.Activity
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/**
 * Eclipsa YouTube Player & Live Eclipsa Audit (v4.0)
 *
 * Clean, TV-first Fire TV Stick Max / Android TV app:
 * 1. Immediate in-app YouTube playback with Chrome 135 Desktop UA ("YT Web + Cursor")
 *    and Smart TV Tizen UA ("YT TV Mode"), full CookieManager + 3rd-party cookies + DOM storage,
 *    hardware acceleration, and HTML5 CustomView fullscreen support.
 * 2. Always-visible On-Screen Virtual Remote Cursor (RemoteCursorOverlayView) with smooth
 *    Fire TV D-Pad movement, auto-scroll at WebView edges, neon click pulse feedback, and
 *    clear Gold (#FFD33D) focus highlight + scale animations on Top Bar & Audit controls.
 * 3. Live Eclipsa Audio & HDMI eARC Auditor HUD combining live YouTube MSE/video stream
 *    telemetry (EclipsaBridge JS hook) with hardware HDMI sink / MediaCodec / Spatializer audit
 *    from LiveSubPassThroughService.
 */
class MainActivity : Activity() {

    private companion object {
        const val URL_YT_WEB = "https://www.youtube.com"
        const val URL_YT_TV = "https://www.youtube.com/tv"

        const val UA_DESKTOP_CHROME =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36"
        const val UA_SMART_TV =
            "Mozilla/5.0 (SMART-TV; LINUX; Tizen 8.0) AppleWebKit/537.36 (KHTML, like Gecko) 108.0.5359.1/8.0 TV Safari/537.36"

        const val COLOR_BG = "#080C14"
        const val COLOR_BAR = "#0F1624"
        const val COLOR_PANEL = "#121A2B"
        const val COLOR_TEXT = "#F2F6FF"
        const val COLOR_MUTED = "#9AB0CC"
        const val COLOR_GOLD = "#FFD33D"
        const val COLOR_CYAN = "#00E5FF"
        const val COLOR_TEAL = "#1FD2B4"
        const val COLOR_GREEN = "#2CE574"
        const val COLOR_AMBER = "#FFB347"
    }

    private enum class PlayerMode {
        YT_WEB,
        YT_TV
    }

    private lateinit var rootFrame: FrameLayout
    private lateinit var mainColumn: LinearLayout
    private lateinit var topBarContainer: LinearLayout
    private lateinit var buttonRow: LinearLayout
    private lateinit var appsLinkRow: LinearLayout
    private lateinit var statusBadge: TextView
    private lateinit var hintText: TextView

    private lateinit var btnYtWeb: Button
    private lateinit var btnYtTv: Button
    private lateinit var btnCursor: Button
    private lateinit var btnAudit: Button
    private lateinit var btnFullscreen: Button

    private lateinit var btnLinkSoundbar: Button
    private lateinit var btnLinkPioneer: Button
    private lateinit var btnLinkOptimizer: Button

    private lateinit var auditCard: ScrollView
    private lateinit var auditStreamSummary: TextView
    private lateinit var auditHardwareSummary: TextView
    private lateinit var auditVerdictText: TextView
    private lateinit var btnRescanAudit: Button
    private lateinit var btnCloseAudit: Button

    private lateinit var playerContainer: FrameLayout
    private lateinit var webView: WebView
    private lateinit var cursorOverlay: RemoteCursorOverlayView
    private lateinit var fullscreenContainer: FrameLayout

    private var customVideoView: View? = null
    private var customVideoCallback: WebChromeClient.CustomViewCallback? = null

    private var currentMode: PlayerMode = PlayerMode.YT_WEB
    private var cursorEnabled: Boolean = true
    private var auditVisible: Boolean = false
    private var appFullscreen: Boolean = false
    private var cursorInitialized: Boolean = false

    private var lastFocusedBarButton: View? = null
    private var cursorClickDownTime: Long = 0L

    private val uiHandler = Handler(Looper.getMainLooper())
    private val telemetryRefreshRunnable = object : Runnable {
        override fun run() {
            refreshTelemetryViews()
            uiHandler.postDelayed(this, 600L)
        }
    }

    // Live YouTube JS bridge telemetry state
    @Volatile private var jsVideoTitle: String = "Loading YouTube..."
    @Volatile private var jsPageUrl: String = URL_YT_WEB
    @Volatile private var jsIsPlaying: Boolean = false
    @Volatile private var jsResolution: String = "--"
    @Volatile private var jsPlaybackTime: String = "0:00 / 0:00"
    @Volatile private var jsAudioDecodedBytes: Long = 0L
    @Volatile private var jsMseCodecs: String = "Probing YouTube MSE stream..."
    @Volatile private var jsBrowserCapabilities: String = "Checking WebView IAMF / EC-3 support..."
    @Volatile private var jsLastUpdateMs: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyImmersiveSystemUi()

        buildCleanTvUi()
        startBackgroundAuditService()
        loadPlayerMode(PlayerMode.YT_WEB, initialLoad = true)

        uiHandler.post(telemetryRefreshRunnable)
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveSystemUi()
        webView.onResume()
        CookieManager.getInstance().flush()
    }

    override fun onPause() {
        CookieManager.getInstance().flush()
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(telemetryRefreshRunnable)
        try {
            webView.destroy()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun applyImmersiveSystemUi() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    private fun startBackgroundAuditService() {
        try {
            val startIntent = Intent(this, LiveSubPassThroughService::class.java).apply {
                action = LiveSubPassThroughService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(startIntent)
            } else {
                startService(startIntent)
            }
            triggerHardwareAuditScan()
        } catch (_: Exception) {
        }
    }

    private fun triggerHardwareAuditScan() {
        try {
            val scanIntent = Intent(this, LiveSubPassThroughService::class.java).apply {
                action = LiveSubPassThroughService.ACTION_RUN_ECLIPSA_AUDIT
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(scanIntent)
            } else {
                startService(scanIntent)
            }
        } catch (_: Exception) {
        }
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    private fun buildCleanTvUi() {
        rootFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor(COLOR_BG))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        mainColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // Top compact Eclipsa Control Bar
        topBarContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(COLOR_BAR))
            setPadding(dp(16), dp(8), dp(16), dp(8))
            clipChildren = false
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val appTitle = TextView(this).apply {
            text = "ECLIPSA YOUTUBE PLAYER"
            setTextColor(Color.parseColor(COLOR_TEXT))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        statusBadge = TextView(this).apply {
            text = "ECLIPSA AUDIT • SCANNING..."
            setTextColor(Color.parseColor(COLOR_GOLD))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(999).toFloat()
                setColor(Color.parseColor("#1B2436"))
                setStroke(dp(1), Color.parseColor(COLOR_TEAL))
            }
        }

        headerRow.addView(appTitle)
        headerRow.addView(statusBadge)

        buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(4))
            clipChildren = false
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        btnYtWeb = createTvBarButton("YT Web + Cursor") {
            loadPlayerMode(PlayerMode.YT_WEB, initialLoad = false)
        }
        btnYtTv = createTvBarButton("YT TV Mode") {
            loadPlayerMode(PlayerMode.YT_TV, initialLoad = false)
        }
        btnCursor = createTvBarButton("Cursor: ON") {
            cursorEnabled = !cursorEnabled
            updateCursorUiState()
        }
        btnAudit = createTvBarButton("Eclipsa Audit") {
            toggleAuditHud(!auditVisible)
        }
        btnFullscreen = createTvBarButton("Fullscreen") {
            toggleAppFullscreen(!appFullscreen)
        }

        // Assign stable IDs and explicit 4-way D-Pad focus wiring
        btnYtWeb.id = View.generateViewId()
        btnYtTv.id = View.generateViewId()
        btnCursor.id = View.generateViewId()
        btnAudit.id = View.generateViewId()
        btnFullscreen.id = View.generateViewId()

        val barButtons = listOf(btnYtWeb, btnYtTv, btnCursor, btnAudit, btnFullscreen)
        barButtons.forEachIndexed { idx, btn ->
            val lp = LinearLayout.LayoutParams(0, dp(42), 1f)
            if (idx < barButtons.lastIndex) {
                lp.marginEnd = dp(8)
            }
            btn.layoutParams = lp
            btn.nextFocusLeftId = if (idx > 0) barButtons[idx - 1].id else btn.id
            btn.nextFocusRightId = if (idx < barButtons.lastIndex) barButtons[idx + 1].id else btn.id
            buttonRow.addView(btn)
        }

        hintText = TextView(this).apply {
            text = "D-Pad Left/Right/Up/Down: Switch Controls & Old Apps  |  Down past Links: Remote Cursor on YouTube  |  MENU: Bar"
            setTextColor(Color.parseColor(COLOR_MUTED))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(2), dp(2), dp(2), 0)
        }

        // Second Row: Direct Navigation Links to Next Screen with All Old Apps & Functions
        appsLinkRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
            clipChildren = false
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        btnLinkSoundbar = createTvBarButton("Soundbar Cali ->") {
            openLegacySuite(0)
        }
        btnLinkPioneer = createTvBarButton("Pioneer Car 4.1 ->") {
            openLegacySuite(1)
        }
        btnLinkOptimizer = createTvBarButton("Old Optimizer, EQ & Tests ->") {
            openLegacySuite(2)
        }

        btnLinkSoundbar.id = View.generateViewId()
        btnLinkPioneer.id = View.generateViewId()
        btnLinkOptimizer.id = View.generateViewId()

        val linkButtons = listOf(btnLinkSoundbar, btnLinkPioneer, btnLinkOptimizer)
        linkButtons.forEachIndexed { idx, btn ->
            val lp = LinearLayout.LayoutParams(0, dp(36), 1f)
            if (idx < linkButtons.lastIndex) lp.marginEnd = dp(8)
            btn.layoutParams = lp
            btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            btn.nextFocusLeftId = if (idx > 0) linkButtons[idx - 1].id else btn.id
            btn.nextFocusRightId = if (idx < linkButtons.lastIndex) linkButtons[idx + 1].id else btn.id
            appsLinkRow.addView(btn)
        }

        // Wire Row 1 <-> Row 2 D-Pad up/down links cleanly
        btnYtWeb.nextFocusDownId = btnLinkSoundbar.id
        btnYtTv.nextFocusDownId = btnLinkSoundbar.id
        btnCursor.nextFocusDownId = btnLinkPioneer.id
        btnAudit.nextFocusDownId = btnLinkPioneer.id
        btnFullscreen.nextFocusDownId = btnLinkOptimizer.id

        btnLinkSoundbar.nextFocusUpId = btnYtWeb.id
        btnLinkPioneer.nextFocusUpId = btnCursor.id
        btnLinkOptimizer.nextFocusUpId = btnFullscreen.id

        topBarContainer.addView(headerRow)
        topBarContainer.addView(buttonRow)
        topBarContainer.addView(appsLinkRow)
        topBarContainer.addView(hintText)

        // Create WebView first so barButtons, links, and Audit HUD focus wiring can reference webView.id safely
        webView = createConfiguredWebView()
        webView.id = View.generateViewId()
        linkButtons.forEach { it.nextFocusDownId = webView.id }

        // Collapsible Live Eclipsa Audit HUD
        auditCard = buildAuditHudCard()
        auditCard.visibility = View.GONE

        // Fullscreen Player Container: WebView + On-Screen Remote Cursor Overlay
        playerContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            setBackgroundColor(Color.BLACK)
        }

        cursorOverlay = RemoteCursorOverlayView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            isClickable = false
            isFocusable = false
        }

        playerContainer.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        playerContainer.addView(cursorOverlay)

        // Wire focus tracking on WebView so Cursor visibly highlights as active vs parked
        webView.setOnFocusChangeListener { _, hasFocus ->
            cursorOverlay.setCursorActive(hasFocus)
        }

        mainColumn.addView(topBarContainer)
        mainColumn.addView(auditCard)
        mainColumn.addView(playerContainer)

        fullscreenContainer = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        rootFrame.addView(mainColumn)
        rootFrame.addView(fullscreenContainer)
        setContentView(rootFrame)

        updateTopBarVisuals()
        updateCursorUiState()

        // Default focus onto Top Bar first button so D-Pad switching works immediately,
        // while keeping the Remote Cursor visibly parked on screen below ready on D-Pad Down.
        lastFocusedBarButton = btnYtWeb
        btnYtWeb.post {
            btnYtWeb.requestFocus()
            ensureInitialCursorPosition()
        }
    }

    private fun createTvBarButton(label: String, onClick: () -> Unit): Button {
        val btn = Button(this).apply {
            text = label
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            isFocusable = true
            isFocusableInTouchMode = false
            setOnClickListener { onClick() }
        }

        btn.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                lastFocusedBarButton = v
            }
            applyBarButtonStyle(v as Button, isButtonActive(v), hasFocus)
        }
        return btn
    }

    private fun isButtonActive(btn: Button): Boolean {
        return when (btn) {
            btnYtWeb -> currentMode == PlayerMode.YT_WEB
            btnYtTv -> currentMode == PlayerMode.YT_TV
            btnCursor -> cursorEnabled
            btnAudit -> auditVisible
            btnFullscreen -> appFullscreen
            else -> false
        }
    }

    private fun applyBarButtonStyle(btn: Button, isActive: Boolean, isFocused: Boolean) {
        val bgColor = when {
            isFocused -> Color.parseColor("#2E260C")
            isActive -> Color.parseColor("#11343C")
            else -> Color.parseColor("#162033")
        }
        val strokeColor = when {
            isFocused -> Color.parseColor(COLOR_GOLD)
            isActive -> Color.parseColor(COLOR_TEAL)
            else -> Color.parseColor("#2B3952")
        }
        val textColor = when {
            isFocused -> Color.parseColor(COLOR_GOLD)
            isActive -> Color.parseColor("#9CF8E8")
            else -> Color.parseColor(COLOR_TEXT)
        }
        val strokeWidth = if (isFocused) dp(3) else dp(1)

        btn.background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(bgColor)
            setStroke(strokeWidth, strokeColor)
        }
        btn.setTextColor(textColor)
        btn.animate()
            .scaleX(if (isFocused) 1.06f else 1.0f)
            .scaleY(if (isFocused) 1.06f else 1.0f)
            .setDuration(110L)
            .start()
        btn.elevation = if (isFocused) dp(8).toFloat() else 0f
    }

    private fun updateTopBarVisuals() {
        val all = listOf(
            btnYtWeb, btnYtTv, btnCursor, btnAudit, btnFullscreen,
            btnLinkSoundbar, btnLinkPioneer, btnLinkOptimizer
        )
        all.forEach { b ->
            applyBarButtonStyle(b, isButtonActive(b), b.hasFocus())
        }
    }

    private fun openLegacySuite(initialTab: Int) {
        val intent = Intent(this, LegacySuiteActivity::class.java).apply {
            putExtra(LegacySuiteActivity.EXTRA_INITIAL_TAB, initialTab)
        }
        startActivity(intent)
    }

    private fun buildAuditHudCard(): ScrollView {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor(COLOR_PANEL))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(230)
            )
        }

        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val title = TextView(this).apply {
            text = "REAL-TIME ECLIPSA AUDIO (IAMF) & HDMI eARC AUDIT"
            setTextColor(Color.parseColor(COLOR_CYAN))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(0, 0, 0, dp(6))
        }

        auditVerdictText = TextView(this).apply {
            text = "Initializing real-time YouTube & HDMI eARC audit..."
            setTextColor(Color.parseColor(COLOR_GOLD))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor("#182132"))
                setStroke(dp(1), Color.parseColor(COLOR_TEAL))
            }
        }

        val columns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        auditStreamSummary = TextView(this).apply {
            text = "Waiting for YouTube playback telemetry..."
            setTextColor(Color.parseColor(COLOR_TEXT))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor("#0D1422"))
                setStroke(dp(1), Color.parseColor("#25344E"))
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        }

        auditHardwareSummary = TextView(this).apply {
            text = "Scanning Fire TV HDMI eARC & MediaCodec capabilities..."
            setTextColor(Color.parseColor(COLOR_TEXT))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor("#0D1422"))
                setStroke(dp(1), Color.parseColor("#25344E"))
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        columns.addView(auditStreamSummary)
        columns.addView(auditHardwareSummary)

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        btnRescanAudit = createAuditActionButton("Re-Scan HDMI & Codec") {
            triggerHardwareAuditScan()
            injectEclipsaTelemetryScript(webView)
            refreshTelemetryViews()
        }
        btnCloseAudit = createAuditActionButton("Hide Audit HUD") {
            toggleAuditHud(false)
            focusTopBarButton(btnAudit)
        }

        btnRescanAudit.id = View.generateViewId()
        btnCloseAudit.id = View.generateViewId()

        btnRescanAudit.layoutParams = LinearLayout.LayoutParams(dp(190), dp(36)).apply {
            marginEnd = dp(10)
        }
        btnCloseAudit.layoutParams = LinearLayout.LayoutParams(dp(160), dp(36))

        btnRescanAudit.nextFocusUpId = btnAudit.id
        btnRescanAudit.nextFocusRightId = btnCloseAudit.id
        btnRescanAudit.nextFocusLeftId = btnRescanAudit.id
        btnRescanAudit.nextFocusDownId = webView.id

        btnCloseAudit.nextFocusUpId = btnFullscreen.id
        btnCloseAudit.nextFocusLeftId = btnRescanAudit.id
        btnCloseAudit.nextFocusRightId = btnCloseAudit.id
        btnCloseAudit.nextFocusDownId = webView.id

        actionRow.addView(btnRescanAudit)
        actionRow.addView(btnCloseAudit)

        inner.addView(title)
        inner.addView(auditVerdictText)
        inner.addView(columns)
        inner.addView(actionRow)
        scroll.addView(inner)
        return scroll
    }

    private fun createAuditActionButton(label: String, onClick: () -> Unit): Button {
        val btn = Button(this).apply {
            text = label
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            isFocusable = true
            isFocusableInTouchMode = false
            setOnClickListener { onClick() }
        }
        btn.setOnFocusChangeListener { v, hasFocus ->
            applyBarButtonStyle(v as Button, false, hasFocus)
        }
        applyBarButtonStyle(btn, false, false)
        return btn
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createConfiguredWebView(): WebView {
        val wv = WebView(this).apply {
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            isFocusable = true
            isFocusableInTouchMode = true
            setBackgroundColor(Color.BLACK)
        }

        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(wv, true)

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = false
            displayZoomControls = false
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
            allowContentAccess = true
            allowFileAccess = false
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = UA_DESKTOP_CHROME
        }

        wv.addJavascriptInterface(EclipsaJsBridge(), "EclipsaBridge")

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                // Keep all YouTube / Google navigation inside this WebView
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url != null) {
                    jsPageUrl = url
                }
                if (view != null) {
                    injectEclipsaTelemetryScript(view)
                }
                CookieManager.getInstance().flush()
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                if (request == null) return
                runOnUiThread {
                    try {
                        val grants = request.resources.filter {
                            it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID
                        }.toTypedArray()
                        if (grants.isNotEmpty()) {
                            request.grant(grants)
                        } else {
                            request.deny()
                        }
                    } catch (_: Exception) {
                        try {
                            request.deny()
                        } catch (_: Exception) {
                        }
                    }
                }
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view == null) {
                    callback?.onCustomViewHidden()
                    return
                }
                if (customVideoView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customVideoView = view
                customVideoCallback = callback
                fullscreenContainer.removeAllViews()
                fullscreenContainer.addView(
                    view,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
                fullscreenContainer.visibility = View.VISIBLE
                mainColumn.visibility = View.GONE
                applyImmersiveSystemUi()
            }

            override fun onHideCustomView() {
                exitCustomVideoFullscreen()
            }
        }

        return wv
    }

    private fun exitCustomVideoFullscreen() {
        val current = customVideoView ?: return
        fullscreenContainer.removeView(current)
        fullscreenContainer.visibility = View.GONE
        mainColumn.visibility = View.VISIBLE
        customVideoView = null
        try {
            customVideoCallback?.onCustomViewHidden()
        } catch (_: Exception) {
        }
        customVideoCallback = null
        applyImmersiveSystemUi()
        webView.requestFocus()
    }

    private fun loadPlayerMode(mode: PlayerMode, initialLoad: Boolean) {
        currentMode = mode
        when (mode) {
            PlayerMode.YT_WEB -> {
                webView.settings.userAgentString = UA_DESKTOP_CHROME
                cursorEnabled = true
                if (!initialLoad || webView.url == null) {
                    webView.loadUrl(URL_YT_WEB)
                } else {
                    webView.loadUrl(URL_YT_WEB)
                }
            }
            PlayerMode.YT_TV -> {
                webView.settings.userAgentString = UA_SMART_TV
                // Keep cursor easily toggleable; in TV mode D-Pad arrows drive native Leanback UI when Cursor OFF
                cursorEnabled = false
                webView.loadUrl(URL_YT_TV)
            }
        }
        updateTopBarVisuals()
        updateCursorUiState()
    }

    private fun updateCursorUiState() {
        btnCursor.text = if (cursorEnabled) "Cursor: ON" else "Cursor: OFF"
        cursorOverlay.visibility = if (cursorEnabled) View.VISIBLE else View.GONE
        cursorOverlay.setCursorActive(cursorEnabled && isFocusInsidePlayer())
        if (cursorEnabled) {
            ensureInitialCursorPosition()
        }
        updateTopBarVisuals()
    }

    private fun ensureInitialCursorPosition() {
        playerContainer.post {
            if (!cursorInitialized && playerContainer.width > 0 && playerContainer.height > 0) {
                cursorOverlay.setPosition(
                    playerContainer.width * 0.50f,
                    playerContainer.height * 0.48f
                )
                cursorInitialized = true
            }
        }
    }

    private fun toggleAuditHud(show: Boolean) {
        auditVisible = show
        auditCard.visibility = if (show) View.VISIBLE else View.GONE

        val linkButtons = listOf(btnLinkSoundbar, btnLinkPioneer, btnLinkOptimizer)
        val nextDownId = if (show) btnRescanAudit.id else webView.id
        linkButtons.forEach { it.nextFocusDownId = nextDownId }

        if (show) {
            triggerHardwareAuditScan()
            injectEclipsaTelemetryScript(webView)
            refreshTelemetryViews()
            btnRescanAudit.post { btnRescanAudit.requestFocus() }
        }
        updateTopBarVisuals()
    }

    private fun toggleAppFullscreen(fullscreen: Boolean) {
        appFullscreen = fullscreen
        if (fullscreen) {
            if (auditVisible) {
                toggleAuditHud(false)
            }
            topBarContainer.visibility = View.GONE
            webView.requestFocus()
        } else {
            topBarContainer.visibility = View.VISIBLE
            focusTopBarButton(btnFullscreen)
        }
        updateTopBarVisuals()
    }

    private fun isFocusInsidePlayer(): Boolean {
        val focused = currentFocus
        return focused == webView || focused == playerContainer
    }

    private fun focusTopBarButton(preferred: View? = null) {
        if (appFullscreen) {
            appFullscreen = false
            topBarContainer.visibility = View.VISIBLE
            updateTopBarVisuals()
        }
        val target = preferred ?: lastFocusedBarButton ?: btnYtWeb
        target.requestFocus()
        cursorOverlay.setCursorActive(false)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode

        // 1. MENU key always toggles between Player and Top Bar (and exits Fullscreen if hidden)
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                if (customVideoView != null) {
                    exitCustomVideoFullscreen()
                } else if (appFullscreen) {
                    toggleAppFullscreen(false)
                } else if (isFocusInsidePlayer()) {
                    focusTopBarButton()
                } else {
                    webView.requestFocus()
                    cursorOverlay.setCursorActive(cursorEnabled)
                }
            }
            return true
        }

        // 2. Intercept D-Pad movement and Center click for the Virtual Remote Cursor when inside Player
        if (customVideoView == null && cursorEnabled && isFocusInsidePlayer()) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        handleCursorMove(keyCode, event.repeatCount)
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER -> {
                    handleCursorClickEvent(event)
                    return true
                }
            }
        }

        return super.dispatchKeyEvent(event)
    }

    private fun handleCursorMove(keyCode: Int, repeatCount: Int) {
        val width = playerContainer.width.toFloat()
        val height = playerContainer.height.toFloat()
        if (width <= 0f || height <= 0f) return

        val density = resources.displayMetrics.density
        val step = when {
            repeatCount >= 12 -> 40f * density
            repeatCount >= 4 -> 26f * density
            else -> 15f * density
        }
        val edgeZone = 34f * density
        val scrollStep = (64f * density).toInt()

        var x = cursorOverlay.cursorX
        var y = cursorOverlay.cursorY

        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> x -= step
            KeyEvent.KEYCODE_DPAD_RIGHT -> x += step
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (y <= 4f * density) {
                    val beforeScrollY = webView.scrollY
                    webView.scrollBy(0, -scrollStep)
                    if (webView.scrollY == beforeScrollY) {
                        // Reached top edge and page didn't scroll further up -> switch cleanly to Top Bar!
                        focusTopBarButton()
                        return
                    }
                    y = 0f
                } else {
                    y -= step
                    if (y < edgeZone && webView.canScrollVertically(-1)) {
                        webView.scrollBy(0, -scrollStep)
                    }
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                y += step
                if (y > height - edgeZone && webView.canScrollVertically(1)) {
                    webView.scrollBy(0, scrollStep)
                }
            }
        }

        x = max(0f, min(width - 1f, x))
        y = max(0f, min(height - 1f, y))
        cursorOverlay.setPosition(x, y)
    }

    private fun handleCursorClickEvent(event: KeyEvent) {
        val x = cursorOverlay.cursorX
        val y = cursorOverlay.cursorY

        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount == 0) {
                cursorClickDownTime = SystemClock.uptimeMillis()
                cursorOverlay.triggerClickPulse()
                val downMotion = MotionEvent.obtain(
                    cursorClickDownTime,
                    cursorClickDownTime,
                    MotionEvent.ACTION_DOWN,
                    x,
                    y,
                    0
                )
                webView.dispatchTouchEvent(downMotion)
                downMotion.recycle()
            }
        } else if (event.action == KeyEvent.ACTION_UP) {
            val now = SystemClock.uptimeMillis()
            val downTime = if (cursorClickDownTime > 0L) cursorClickDownTime else now
            val upMotion = MotionEvent.obtain(
                downTime,
                now,
                MotionEvent.ACTION_UP,
                x,
                y,
                0
            )
            webView.dispatchTouchEvent(upMotion)
            upMotion.recycle()
            cursorClickDownTime = 0L
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            customVideoView != null -> exitCustomVideoFullscreen()
            auditVisible -> {
                toggleAuditHud(false)
                focusTopBarButton(btnAudit)
            }
            appFullscreen -> toggleAppFullscreen(false)
            isFocusInsidePlayer() && webView.canGoBack() -> webView.goBack()
            isFocusInsidePlayer() -> focusTopBarButton()
            else -> super.onBackPressed()
        }
    }

    private fun injectEclipsaTelemetryScript(view: WebView) {
        val js = """
            (function() {
                try {
                    if (!window.__eclipsaMseCodecs) {
                        window.__eclipsaMseCodecs = [];
                    }
                    if (!window.__eclipsaHooked && window.MediaSource && MediaSource.prototype) {
                        window.__eclipsaHooked = true;
                        var origAdd = MediaSource.prototype.addSourceBuffer;
                        MediaSource.prototype.addSourceBuffer = function(mime) {
                            try {
                                if (mime && window.__eclipsaMseCodecs.indexOf(mime) === -1) {
                                    window.__eclipsaMseCodecs.push(mime);
                                    if (window.__eclipsaMseCodecs.length > 6) {
                                        window.__eclipsaMseCodecs.shift();
                                    }
                                }
                            } catch (e) {}
                            return origAdd.apply(this, arguments);
                        };
                    }

                    function fmtTime(sec) {
                        if (!isFinite(sec) || sec < 0) return '0:00';
                        sec = Math.floor(sec);
                        var m = Math.floor(sec / 60);
                        var s = sec % 60;
                        return m + ':' + (s < 10 ? '0' : '') + s;
                    }

                    function reportEclipsa() {
                        try {
                            var videos = Array.prototype.slice.call(document.querySelectorAll('video'));
                            var v = null;
                            for (var i = 0; i < videos.length; i++) {
                                if (!videos[i].paused && videos[i].readyState >= 2) {
                                    v = videos[i];
                                    break;
                                }
                            }
                            if (!v && videos.length > 0) v = videos[0];

                            var caps = [];
                            if (window.MediaSource && MediaSource.isTypeSupported) {
                                if (MediaSource.isTypeSupported('audio/mp4; codecs="iamf"')) caps.push('IAMF:YES');
                                else caps.push('IAMF:NO');
                                if (MediaSource.isTypeSupported('audio/mp4; codecs="ec-3"')) caps.push('EC-3:YES');
                                else caps.push('EC-3:NO');
                                if (MediaSource.isTypeSupported('audio/webm; codecs="opus"')) caps.push('OPUS:YES');
                                if (MediaSource.isTypeSupported('audio/mp4; codecs="mp4a.40.2"')) caps.push('AAC:YES');
                            }

                            var res = '--';
                            var playing = false;
                            var timeStr = '0:00 / 0:00';
                            var audioBytes = 0;
                            if (v) {
                                playing = (!v.paused && !v.ended && v.readyState >= 2);
                                if (v.videoWidth > 0 && v.videoHeight > 0) {
                                    res = v.videoWidth + 'x' + v.videoHeight;
                                }
                                timeStr = fmtTime(v.currentTime) + ' / ' + fmtTime(v.duration);
                                audioBytes = v.webkitAudioDecodedByteCount || 0;
                            }

                            var title = (document.title || 'YouTube').replace(/\s*-\s*YouTube$/i, '').trim();
                            var payload = {
                                title: title,
                                url: location.href,
                                playing: playing,
                                resolution: res,
                                timeStr: timeStr,
                                audioBytes: audioBytes,
                                mseCodecs: window.__eclipsaMseCodecs.join(' | '),
                                caps: caps.join('  ')
                            };
                            if (window.EclipsaBridge && window.EclipsaBridge.onTelemetry) {
                                window.EclipsaBridge.onTelemetry(JSON.stringify(payload));
                            }
                        } catch (err) {}
                    }

                    reportEclipsa();
                    if (!window.__eclipsaTimer) {
                        window.__eclipsaTimer = setInterval(reportEclipsa, 900);
                    }
                } catch (e) {}
            })();
        """.trimIndent()
        view.evaluateJavascript(js, null)
    }

    inner class EclipsaJsBridge {
        @JavascriptInterface
        fun onTelemetry(jsonText: String) {
            try {
                val obj = JSONObject(jsonText)
                jsVideoTitle = obj.optString("title", "YouTube").ifBlank { "YouTube" }
                jsPageUrl = obj.optString("url", URL_YT_WEB)
                jsIsPlaying = obj.optBoolean("playing", false)
                jsResolution = obj.optString("resolution", "--")
                jsPlaybackTime = obj.optString("timeStr", "0:00 / 0:00")
                jsAudioDecodedBytes = obj.optLong("audioBytes", 0L)
                val mse = obj.optString("mseCodecs", "")
                jsMseCodecs = if (mse.isBlank()) "Waiting for active <video> audio track..." else mse
                jsBrowserCapabilities = obj.optString("caps", "IAMF/EC-3 checked")
                jsLastUpdateMs = System.currentTimeMillis()
            } catch (_: Exception) {
            }
        }
    }

    private fun refreshTelemetryViews() {
        val hwScore = LiveSubPassThroughService.eclipsaAuditScore
        val hwVerdict = LiveSubPassThroughService.eclipsaAuditVerdict
        val hdmiReport = LiveSubPassThroughService.eclipsaHdmiSinkReport
        val codecReport = LiveSubPassThroughService.eclipsaCodecReport
        val chReport = LiveSubPassThroughService.eclipsaChannelMapReport
        val activeApp = NowPlayingListenerService.currentAppName
        val pulse = LiveSubPassThroughService.passThroughPulse

        val mseLower = jsMseCodecs.lowercase()
        val hasIamfMse = mseLower.contains("iamf")
        val hasEc3Mse = mseLower.contains("ec-3") || mseLower.contains("eac3")

        val streamTag = when {
            hasIamfMse -> "IAMF STREAM"
            hasEc3Mse -> "E-AC3 STREAM"
            jsIsPlaying -> "YT PLAYING"
            else -> "YT READY"
        }

        statusBadge.text = "$streamTag • AUDIT $hwScore/100"

        if (!auditVisible) return

        val audioKb = jsAudioDecodedBytes / 1024L
        auditStreamSummary.text = buildString {
            appendLine("YOUTUBE STREAM AUDIT")
            appendLine("Title: $jsVideoTitle")
            appendLine("State: ${if (jsIsPlaying) "PLAYING" else "PAUSED / IDLE"}  |  Res: $jsResolution  |  Time: $jsPlaybackTime")
            appendLine("Audio Decoded: ${audioKb} KB  |  Mode: ${if (currentMode == PlayerMode.YT_TV) "YT TV" else "YT Web"}")
            appendLine("Negotiated MSE: $jsMseCodecs")
            append("WebView Probe: $jsBrowserCapabilities")
        }

        auditHardwareSummary.text = buildString {
            appendLine("FIRE TV -> SOUNDBAR HDMI eARC AUDIT")
            appendLine("Active Audio App: $activeApp  |  Signal Level: $pulse%")
            appendLine("HDMI Sink: $hdmiReport")
            appendLine("Codecs: $codecReport")
            append("Spatializer / Map: $chReport")
        }

        val combinedVerdict = when {
            hasIamfMse -> "REAL ECLIPSA IAMF STREAM DETECTED ($hwScore/100 HW Score) — $hwVerdict"
            hasEc3Mse -> "DOLBY E-AC3 MULTICHANNEL STREAM ACTIVE ($hwScore/100 HW Score) — $hwVerdict"
            jsIsPlaying -> "YOUTUBE AUDIO PLAYING (Browser MSE: ${if (jsBrowserCapabilities.contains("IAMF:YES")) "IAMF Ready" else "PCM/Opus/AAC Bridge"}) — HW $hwScore/100 ($hwVerdict)"
            else -> "AUDIT SCORE $hwScore/100 — $hwVerdict (Start any YouTube video to inspect live MSE audio codec)"
        }
        auditVerdictText.text = combinedVerdict
    }

    /**
     * Bright, always-clear Virtual Remote Cursor overlay for Fire TV D-Pad navigation.
     */
    private class RemoteCursorOverlayView(context: Context) : View(context) {
        var cursorX: Float = 0f
            private set
        var cursorY: Float = 0f
            private set

        private var active: Boolean = false
        private var clickPulseUntil: Long = 0L

        private val density = context.resources.displayMetrics.density

        private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f * density
            color = Color.parseColor(COLOR_CYAN)
        }

        private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(110, 0, 229, 255)
        }

        private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f * density
            strokeJoin = Paint.Join.ROUND
            color = Color.parseColor("#080C14")
        }

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor(COLOR_GOLD)
        }

        private val tipDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor(COLOR_CYAN)
        }

        private val arrowPath = Path()

        fun setPosition(x: Float, y: Float) {
            cursorX = x
            cursorY = y
            invalidate()
        }

        fun setCursorActive(isActive: Boolean) {
            active = isActive
            invalidate()
        }

        fun triggerClickPulse() {
            clickPulseUntil = SystemClock.uptimeMillis() + 200L
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val now = SystemClock.uptimeMillis()
            val alpha = if (active) 255 else 125

            // Click ripple feedback
            if (now < clickPulseUntil) {
                val frac = 1f - ((clickPulseUntil - now).toFloat() / 200f)
                pulsePaint.alpha = ((1f - frac) * 140).toInt()
                canvas.drawCircle(cursorX, cursorY, (12f + 22f * frac) * density, pulsePaint)
                postInvalidateDelayed(16L)
            }

            // Glowing circular reticle around cursor tip
            haloPaint.alpha = if (active) 235 else 110
            canvas.drawCircle(cursorX, cursorY, 13f * density, haloPaint)

            // Crisp TV Arrow pointer originating at (cursorX, cursorY)
            val s = density * 1.25f
            arrowPath.reset()
            arrowPath.moveTo(cursorX, cursorY)
            arrowPath.lineTo(cursorX, cursorY + 21f * s)
            arrowPath.lineTo(cursorX + 5.5f * s, cursorY + 16.5f * s)
            arrowPath.lineTo(cursorX + 10.5f * s, cursorY + 24.5f * s)
            arrowPath.lineTo(cursorX + 13.8f * s, cursorY + 22.8f * s)
            arrowPath.lineTo(cursorX + 9f * s, cursorY + 15f * s)
            arrowPath.lineTo(cursorX + 15.5f * s, cursorY + 14.5f * s)
            arrowPath.close()

            outlinePaint.alpha = alpha
            fillPaint.alpha = alpha
            tipDotPaint.alpha = alpha

            canvas.drawPath(arrowPath, outlinePaint)
            canvas.drawPath(arrowPath, fillPaint)
            canvas.drawCircle(cursorX, cursorY, 3f * density, tipDotPaint)
        }
    }
}
