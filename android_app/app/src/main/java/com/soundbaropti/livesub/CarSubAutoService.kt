package com.soundbaropti.livesub

import android.content.Intent
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.browse.MediaBrowser.MediaItem
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.service.media.MediaBrowserService

/**
 * Lightweight, zero-dependency Android Auto Dashboard Service for Pioneer DMH-Z5290BT.
 * Uses native Android framework MediaBrowserService + MediaSession so the APK stays ultra-small
 * and installs in under 1 second.
 */
class CarSubAutoService : MediaBrowserService() {

    private lateinit var mediaSession: MediaSession

    override fun onCreate() {
        super.onCreate()
        mediaSession = MediaSession(this, "CarSubAutoSession").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    startGuardService()
                    updateCarNowPlayingState()
                }

                override fun onPause() {
                    NowPlayingListenerService.playPause(this@CarSubAutoService)
                    updateCarNowPlayingState()
                }

                override fun onSkipToNext() {
                    NowPlayingListenerService.nextTrack(this@CarSubAutoService)
                    updateCarNowPlayingState()
                }

                override fun onSkipToPrevious() {
                    NowPlayingListenerService.prevTrack(this@CarSubAutoService)
                    updateCarNowPlayingState()
                }

                override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                    when (mediaId) {
                        "toggle_guard" -> {
                            val svc = Intent(this@CarSubAutoService, LiveSubPassThroughService::class.java)
                            if (LiveSubPassThroughService.isRunning) {
                                svc.action = LiveSubPassThroughService.ACTION_STOP
                                startService(svc)
                            } else {
                                startGuardService()
                            }
                        }
                        "toggle_bypass" -> {
                            LiveSubPassThroughService.isBypassed = !LiveSubPassThroughService.isBypassed
                        }
                        "preset_velvet" -> {
                            startGuardService()
                            LiveSubPassThroughService.applyPreset("DEEP_VELVET")
                        }
                        "preset_surround" -> {
                            startGuardService()
                            LiveSubPassThroughService.applyPreset("SURROUND_360")
                        }
                        "preset_vocal" -> {
                            startGuardService()
                            LiveSubPassThroughService.applyPreset("PURE_VOCAL")
                        }
                        "preset_loudness" -> {
                            startGuardService()
                            LiveSubPassThroughService.applyPreset("LOUDNESS_RICH")
                        }
                        "run_auto_cal" -> {
                            val svc = Intent(this@CarSubAutoService, LiveSubPassThroughService::class.java).apply {
                                action = LiveSubPassThroughService.ACTION_RUN_SWEEP
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                startForegroundService(svc)
                            } else {
                                startService(svc)
                            }
                        }
                    }
                    updateCarNowPlayingState()
                    notifyChildrenChanged("root")
                }

                override fun onCustomAction(action: String, extras: Bundle?) {
                    onPlayFromMediaId(action, extras)
                }
            })
            isActive = true
        }
        sessionToken = mediaSession.sessionToken
        updateCarNowPlayingState()
    }

    private fun startGuardService() {
        val svc = Intent(this, LiveSubPassThroughService::class.java).apply {
            action = LiveSubPassThroughService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svc)
        } else {
            startService(svc)
        }
    }

    private fun updateCarNowPlayingState() {
        val running = LiveSubPassThroughService.isRunning
        val stateCode = if (running) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED

        val stateBuilder = PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                PlaybackState.ACTION_PLAY_FROM_MEDIA_ID or
                PlaybackState.ACTION_PLAY_FROM_SEARCH
            )
            .setState(stateCode, 0L, 1.0f)

        mediaSession.setPlaybackState(stateBuilder.build())

        val songTitle = NowPlayingListenerService.currentTitle
        val dspSummary = if (!running) {
            "DSP Stopped — Tap to Start 360° Optimizer"
        } else if (LiveSubPassThroughService.isBypassed) {
            "A/B BYPASS MODE (Raw Unprocessed Audio)"
        } else {
            "Deep Sub %+.1f dB | Anti-Hit %.1f dB | 360° %+.1f dB".format(
                LiveSubPassThroughService.liveDeepGainDb,
                LiveSubPassThroughService.liveCutDb,
                LiveSubPassThroughService.liveMidGainDb
            )
        }

        val meta = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, "$songTitle (${NowPlayingListenerService.currentAppName})")
            .putString(MediaMetadata.METADATA_KEY_ARTIST, dspSummary)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, "Pioneer DMH-Z5290BT 360° Live Optimizer")
            .build()
        mediaSession.setMetadata(meta)
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot {
        val extras = Bundle().apply {
            putBoolean("android.media.browse.CONTENT_STYLE_SUPPORTED", true)
            putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", 1)
            putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", 1)
        }
        return BrowserRoot("root", extras)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaItem>>
    ) {
        updateCarNowPlayingState()
        val items = mutableListOf<MediaItem>()

        val statusSubtitle = if (LiveSubPassThroughService.isRunning) {
            "ACTIVE • Deep Sub %+.1f dB • Anti-Hit %.1f dB (Tap to Stop)".format(
                LiveSubPassThroughService.liveDeepGainDb,
                LiveSubPassThroughService.liveCutDb
            )
        } else {
            "STOPPED • Tap to Start 360° Live Mic Optimizer"
        }

        val nowPlayingSub = "${NowPlayingListenerService.currentTitle} — ${NowPlayingListenerService.currentArtist}"

        items.add(
            buildItem(
                "toggle_guard",
                "🟢 360° Live Sound & Sub Optimizer",
                "$statusSubtitle | Playing: $nowPlayingSub"
            )
        )
        items.add(
            buildItem(
                "preset_velvet",
                "✨ Preset: Deep Velvet Bass (No Hard Hit)",
                "Warm 30-58 Hz background cushion + aggressive 80-135 Hz slam suppression"
            )
        )
        items.add(
            buildItem(
                "preset_surround",
                "🎭 Preset: 360° Surround Concert Hall",
                "Balanced Front Component + Rear Coaxial immersion & wide vocals"
            )
        )
        items.add(
            buildItem(
                "preset_vocal",
                "💎 Preset: Pure Vocal & Silk Tweeter",
                "Crystal midrange clarity + zero tweeter harshness + tight sub"
            )
        )
        items.add(
            buildItem(
                "preset_loudness",
                "🔥 Preset: Low-Volume Rich Loudness",
                "Full deep bass & crisp detail even at low cabin volume"
            )
        )
        items.add(
            buildItem(
                "toggle_bypass",
                if (LiveSubPassThroughService.isBypassed) "🔊 A/B Mode: RAW BYPASS (Tap for 360° DSP)" else "🔊 A/B Mode: 360° DSP ON (Tap to Compare Raw)",
                "Instant live A/B comparison while Spotify / YouTube Music plays"
            )
        )
        items.add(
            buildItem(
                "run_auto_cal",
                "🎯 Auto-Calibrate Front + Rear + Subwoofer",
                "Plays 4-sec harmonic sweep & auto-levels all cabin speakers via phone mic"
            )
        )

        result.sendResult(items)
    }

    private fun buildItem(id: String, title: String, subtitle: String): MediaItem {
        val desc = MediaDescription.Builder()
            .setMediaId(id)
            .setTitle(title)
            .setSubtitle(subtitle)
            .build()
        return MediaItem(desc, MediaItem.FLAG_PLAYABLE)
    }

    override fun onDestroy() {
        mediaSession.release()
        super.onDestroy()
    }
}
