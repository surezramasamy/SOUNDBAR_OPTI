package com.soundbaropti.livesub

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var statusBadge: TextView
    private lateinit var bassValText: TextView
    private lateinit var midValText: TextView
    private lateinit var cutValText: TextView
    private lateinit var sessionText: TextView
    private lateinit var bassProgress: ProgressBar
    private lateinit var cutProgress: ProgressBar
    private lateinit var toggleBtn: Button
    private lateinit var threshValueLabel: TextView
    private lateinit var maxCutValueLabel: TextView

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
        uiHandler.post(updateRunnable)
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(updateRunnable)
    }

    private fun requestNeededPermissions() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 200)
        }
    }

    private fun toggleService() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestNeededPermissions()
            return
        }
        val intent = Intent(this, LiveSubPassThroughService::class.java)
        if (LiveSubPassThroughService.isRunning) {
            intent.action = LiveSubPassThroughService.ACTION_STOP
            startService(intent)
        } else {
            intent.action = LiveSubPassThroughService.ACTION_START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
    }

    private fun refreshMeters() {
        val running = LiveSubPassThroughService.isRunning
        statusBadge.text = if (running) "● ACTIVE (BACKGROUND + ANDROID AUTO)" else "○ STOPPED"
        statusBadge.setTextColor(if (running) Color.parseColor("#3FB950") else Color.parseColor("#8B949E"))

        toggleBtn.text = if (running) "⏹ STOP LIVE WOOFER GUARD" else "▶ START LIVE WOOFER GUARD"
        val btnBg = GradientDrawable().apply {
            cornerRadius = 24f
            setColor(if (running) Color.parseColor("#F85149") else Color.parseColor("#3FB950"))
        }
        toggleBtn.background = btnBg
        toggleBtn.setTextColor(if (running) Color.WHITE else Color.BLACK)

        val subDb = LiveSubPassThroughService.liveSubDb
        val midDb = LiveSubPassThroughService.liveMidDb
        val cutDb = LiveSubPassThroughService.liveCutDb

        bassValText.text = "%.1f dB".format(subDb)
        midValText.text = "%.1f dB".format(midDb)
        cutValText.text = "%.1f dB".format(cutDb)
        cutValText.setTextColor(if (cutDb < -1.0f) Color.parseColor("#FF7B00") else Color.parseColor("#3FB950"))

        bassProgress.progress = ((subDb - 35f) / 60f * 100f).toInt().coerceIn(0, 100)
        cutProgress.progress = (kotlin.math.abs(cutDb) / LiveSubPassThroughService.maxSubCutDb * 100f).toInt().coerceIn(0, 100)

        val pkgs = LiveSubPassThroughService.activePackages.values.distinct()
        sessionText.text = if (pkgs.isEmpty()) {
            "Intercepting: Global Output Mix (Start playing a song in Spotify / YouTube Music)"
        } else {
            "Intercepting Active Players: ${pkgs.joinToString(", ")}"
        }
    }

    private fun cardBg(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = 32f
        setColor(Color.parseColor("#161B22"))
        setStroke(3, Color.parseColor("#30363D"))
    }

    private fun buildUi(): ScrollView {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0D1117"))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(42, 56, 42, 56)
        }

        val title = TextView(this).apply {
            text = "Live Woofer Auto-Guard"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        root.addView(title)

        statusBadge = TextView(this).apply {
            text = "○ STOPPED"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 8, 0, 24)
        }
        root.addView(statusBadge)

        val descCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg()
            setPadding(36, 32, 36, 32)
        }

        sessionText = TextView(this).apply {
            text = "Intercepting: Global Output Mix"
            textSize = 13f
            setTextColor(Color.parseColor("#58A6FF"))
            setPadding(0, 0, 0, 20)
        }
        descCard.addView(sessionText)

        toggleBtn = Button(this).apply {
            text = "▶ START LIVE WOOFER GUARD"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(24, 32, 24, 32)
            setOnClickListener { toggleService() }
        }
        descCard.addView(toggleBtn)
        root.addView(descCard)

        val meterCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg()
            setPadding(36, 32, 36, 32)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = 32
            layoutParams = lp
        }

        val bassLabel = TextView(this).apply {
            text = "CABIN SUB-BASS (30 - 95 Hz)"
            textSize = 12f
            setTextColor(Color.parseColor("#8B949E"))
        }
        meterCard.addView(bassLabel)

        bassValText = TextView(this).apply {
            text = "40.0 dB"
            textSize = 26f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        meterCard.addView(bassValText)

        bassProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 10
        }
        meterCard.addView(bassProgress)

        val midLabel = TextView(this).apply {
            text = "VOCALS / MIDS (250 - 2000 Hz)"
            textSize = 12f
            setTextColor(Color.parseColor("#8B949E"))
            setPadding(0, 24, 0, 0)
        }
        meterCard.addView(midLabel)

        midValText = TextView(this).apply {
            text = "40.0 dB"
            textSize = 20f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.parseColor("#C9D1D9"))
        }
        meterCard.addView(midValText)

        val cutLabel = TextView(this).apply {
            text = "LIVE SUBWOOFER REDUCTION APPLIED TO SPOTIFY / AUTO"
            textSize = 12f
            setTextColor(Color.parseColor("#8B949E"))
            setPadding(0, 28, 0, 0)
        }
        meterCard.addView(cutLabel)

        cutValText = TextView(this).apply {
            text = "0.0 dB"
            textSize = 28f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.parseColor("#3FB950"))
        }
        meterCard.addView(cutValText)

        cutProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        meterCard.addView(cutProgress)
        root.addView(meterCard)

        val sliderCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg()
            setPadding(36, 32, 36, 32)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = 32
            layoutParams = lp
        }

        threshValueLabel = TextView(this).apply {
            text = "Bass Ceiling Threshold: +4.0 dB above Mids (Lower = Cuts Boom Sooner)"
            textSize = 13f
            setTextColor(Color.WHITE)
        }
        sliderCard.addView(threshValueLabel)

        val threshSeek = SeekBar(this).apply {
            max = 14
            progress = 6 // Maps to -2..+12 dB
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val db = (p - 2).toFloat()
                    LiveSubPassThroughService.targetBassOverMidsDb = db
                    threshValueLabel.text = "Bass Ceiling Threshold: %+.1f dB above Mids".format(db)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        sliderCard.addView(threshSeek)

        maxCutValueLabel = TextView(this).apply {
            text = "Max Subwoofer Cut Limit: -10 dB"
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(0, 24, 0, 0)
        }
        sliderCard.addView(maxCutValueLabel)

        val maxCutSeek = SeekBar(this).apply {
            max = 12
            progress = 7 // 3..15 dB
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val db = (p + 3).toFloat()
                    LiveSubPassThroughService.maxSubCutDb = db
                    maxCutValueLabel.text = "Max Subwoofer Cut Limit: -%.0f dB".format(db)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        sliderCard.addView(maxCutSeek)

        val autoNote = TextView(this).apply {
            text = "🚗 Android Auto Ready: Connect your phone to Pioneer DMH-Z5290BT. This service runs in the background while Spotify / YouTube Music plays, and also appears in your Android Auto app launcher."
            textSize = 12f
            setTextColor(Color.parseColor("#8B949E"))
            setPadding(0, 28, 0, 0)
            gravity = Gravity.START
        }
        sliderCard.addView(autoNote)
        root.addView(sliderCard)

        scroll.addView(root)
        return scroll
    }
}
