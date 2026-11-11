package com.soundbaropti.livesub

import android.content.Intent
import android.media.MediaDescription
import android.media.browse.MediaBrowser.MediaItem
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.service.media.MediaBrowserService

/**
 * Android Auto Dashboard Integration for Pioneer DMH-Z5290BT.
 * Lets the driver toggle Live Woofer Guard and switch bass thresholds directly on the car screen.
 */
class CarSubAutoService : MediaBrowserService() {

    private lateinit var mediaSession: MediaSession

    override fun onCreate() {
        super.onCreate()
        mediaSession = MediaSession(this, "CarSubAutoSession").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                    when (mediaId) {
                        "toggle_guard" -> {
                            val svc = Intent(this@CarSubAutoService, LiveSubPassThroughService::class.java)
                            svc.action = if (LiveSubPassThroughService.isRunning)
                                LiveSubPassThroughService.ACTION_STOP
                            else
                                LiveSubPassThroughService.ACTION_START
                            startService(svc)
                        }
                        "preset_tight" -> LiveSubPassThroughService.targetBassOverMidsDb = 2.0f
                        "preset_balanced" -> LiveSubPassThroughService.targetBassOverMidsDb = 4.0f
                        "preset_deep" -> LiveSubPassThroughService.targetBassOverMidsDb = 7.0f
                    }
                    notifyChildrenChanged("root")
                }
            })
            val state = PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PLAY_FROM_MEDIA_ID)
                .setState(PlaybackState.STATE_PAUSED, 0, 1.0f)
                .build()
            setPlaybackState(state)
            isActive = true
        }
        sessionToken = mediaSession.sessionToken
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot {
        return BrowserRoot("root", null)
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaItem>>) {
        val items = mutableListOf<MediaItem>()
        val statusText = if (LiveSubPassThroughService.isRunning)
            "Active (Cut: %.1f dB) — Tap to Stop".format(LiveSubPassThroughService.liveCutDb)
        else
            "Stopped — Tap to Start Live Mic Guard"

        items.add(buildItem("toggle_guard", "Live Woofer Auto-Guard", statusText))
        items.add(buildItem("preset_tight", "Preset: Tight Anti-Boom (+2 dB Ceiling)", "Cuts cabin boom aggressively"))
        items.add(buildItem("preset_balanced", "Preset: Balanced (+4 dB Ceiling)", "Recommended for daily driving"))
        items.add(buildItem("preset_deep", "Preset: Deep Punch (+7 dB Ceiling)", "Allows louder bass before ducking"))

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
