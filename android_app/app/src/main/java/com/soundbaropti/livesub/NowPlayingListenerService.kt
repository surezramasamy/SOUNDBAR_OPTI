package com.soundbaropti.livesub

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.KeyEvent

/**
 * Captures live Now Playing song metadata (Title, Artist, Album, Player App, Play/Pause state)
 * from Spotify, YouTube Music, Apple Music, JioSaavn, etc., and provides media transport controls
 * so the user can directly witness and control the song passing through the 360° DSP engine.
 */
class NowPlayingListenerService : NotificationListenerService() {

    companion object {
        @Volatile var currentTitle: String = "Waiting for Music Player..."
        @Volatile var currentArtist: String = "Play any song in Spotify / YouTube Music"
        @Volatile var currentAlbum: String = ""
        @Volatile var currentAppName: String = "Global Audio Mix"
        @Volatile var currentPackage: String = ""
        @Volatile var isPlaying: Boolean = false

        @Volatile private var activeController: MediaController? = null

        fun friendlyAppName(pkg: String): String {
            val lower = pkg.lowercase()
            return when {
                lower.contains("spotify") -> "Spotify"
                lower.contains("youtube.music") || lower.contains("vanced") -> "YouTube Music"
                lower.contains("youtube") -> "YouTube"
                lower.contains("apple") -> "Apple Music"
                lower.contains("saavn") -> "JioSaavn"
                lower.contains("gaana") -> "Gaana"
                lower.contains("wynk") -> "Wynk Music"
                lower.contains("amazon.mp3") -> "Amazon Music"
                lower.contains("poweramp") -> "Poweramp"
                lower.contains("vlc") -> "VLC"
                pkg.isNotBlank() -> pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
                else -> "System Audio Stream"
            }
        }

        fun isNotificationAccessGranted(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            return flat.contains(context.packageName)
        }

        fun playPause(context: Context) {
            val ctrl = activeController
            if (ctrl != null) {
                val state = ctrl.playbackState?.state
                if (state == PlaybackState.STATE_PLAYING) {
                    ctrl.transportControls.pause()
                } else {
                    ctrl.transportControls.play()
                }
                return
            }
            sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        }

        fun nextTrack(context: Context) {
            val ctrl = activeController
            if (ctrl != null) {
                ctrl.transportControls.skipToNext()
                return
            }
            sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_NEXT)
        }

        fun prevTrack(context: Context) {
            val ctrl = activeController
            if (ctrl != null) {
                ctrl.transportControls.skipToPrevious()
                return
            }
            sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        }

        private fun sendMediaKey(context: Context, keyCode: Int) {
            try {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            } catch (_: Exception) {}
        }

        fun refreshFromMediaSessionManager(context: Context) {
            if (!isNotificationAccessGranted(context)) return
            try {
                val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
                val comp = ComponentName(context, NowPlayingListenerService::class.java)
                val controllers = msm.getActiveSessions(comp)
                pickBestController(controllers)
            } catch (_: Exception) {}
        }

        private fun pickBestController(controllers: List<MediaController>?) {
            if (controllers.isNullOrEmpty()) return
            // Ignore our own CarSubAutoService session so we always show the real music player (Spotify/YTMusic)
            val external = controllers.filter { !it.packageName.contains("soundbaropti") }
            if (external.isEmpty()) return

            val playingCtrl = external.firstOrNull {
                it.playbackState?.state == PlaybackState.STATE_PLAYING
            } ?: external.first()

            activeController = playingCtrl
            extractControllerInfo(playingCtrl)
        }

        private fun extractControllerInfo(ctrl: MediaController) {
            currentPackage = ctrl.packageName ?: ""
            currentAppName = friendlyAppName(currentPackage)
            val state = ctrl.playbackState?.state
            isPlaying = (state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING)

            val meta = ctrl.metadata
            if (meta != null) {
                val title = meta.getString(MediaMetadata.METADATA_KEY_TITLE)
                    ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                val artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                    ?: meta.getString(MediaMetadata.METADATA_KEY_AUTHOR)
                val album = meta.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""

                if (!title.isNullOrBlank()) {
                    currentTitle = title
                    currentArtist = if (!artist.isNullOrBlank()) artist else currentAppName
                    currentAlbum = album
                }
            }
        }
    }

    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        pickBestController(controllers)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        try {
            val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val comp = ComponentName(this, NowPlayingListenerService::class.java)
            msm.addOnActiveSessionsChangedListener(sessionListener, comp)
            pickBestController(msm.getActiveSessions(comp))
        } catch (_: Exception) {}
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        refreshFromMediaSessionManager(this)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        refreshFromMediaSessionManager(this)
    }

    override fun onListenerDisconnected() {
        try {
            val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            msm.removeOnActiveSessionsChangedListener(sessionListener)
        } catch (_: Exception) {}
        super.onListenerDisconnected()
    }
}
