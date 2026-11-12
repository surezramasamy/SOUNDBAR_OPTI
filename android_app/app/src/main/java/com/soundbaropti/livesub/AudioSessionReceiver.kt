package com.soundbaropti.livesub

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect

class AudioSessionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        if (action == AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION ||
            action == AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION
        ) {
            val sessionId = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
            val pkgName = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME) ?: "Media App"
            if (sessionId <= 0) return

            if (action == AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION) {
                LiveSubPassThroughService.activePackages[sessionId] = pkgName
                NowPlayingListenerService.currentPackage = pkgName
                NowPlayingListenerService.currentAppName = NowPlayingListenerService.friendlyAppName(pkgName)
                NowPlayingListenerService.isPlaying = true
                if (NowPlayingListenerService.currentTitle == "Ready for Music Stream") {
                    NowPlayingListenerService.currentTitle = "Streaming via ${NowPlayingListenerService.currentAppName}"
                    NowPlayingListenerService.currentArtist = "Audio Session #$sessionId Locked"
                }
                if (LiveSubPassThroughService.isRunning) {
                    val svc = Intent(context, LiveSubPassThroughService::class.java).apply {
                        this.action = LiveSubPassThroughService.ACTION_ATTACH_SESSION
                        putExtra(LiveSubPassThroughService.EXTRA_SESSION_ID, sessionId)
                        putExtra(LiveSubPassThroughService.EXTRA_PACKAGE_NAME, pkgName)
                    }
                    context.startService(svc)
                }
            } else {
                LiveSubPassThroughService.activePackages.remove(sessionId)
                if (LiveSubPassThroughService.isRunning) {
                    val svc = Intent(context, LiveSubPassThroughService::class.java).apply {
                        this.action = LiveSubPassThroughService.ACTION_DETACH_SESSION
                        putExtra(LiveSubPassThroughService.EXTRA_SESSION_ID, sessionId)
                    }
                    context.startService(svc)
                }
            }
        } else {
            // Capture Track / Artist / Album from Spotify & Android Music Player broadcasts
            val track = intent.getStringExtra("track") ?: intent.getStringExtra("title")
            val artist = intent.getStringExtra("artist")
            val album = intent.getStringExtra("album")
            if (!track.isNullOrBlank()) {
                NowPlayingListenerService.currentTitle = track
                if (!artist.isNullOrBlank()) NowPlayingListenerService.currentArtist = artist
                if (!album.isNullOrBlank()) NowPlayingListenerService.currentAlbum = album
                if (action.contains("spotify")) {
                    NowPlayingListenerService.currentAppName = "Spotify"
                }
                NowPlayingListenerService.isPlaying = intent.getBooleanExtra("playing", true)
            }
        }
    }
}
