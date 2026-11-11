package com.soundbaropti.livesub

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect

class AudioSessionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val sessionId = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
        val pkgName = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME) ?: "Media App"
        if (sessionId <= 0) return

        when (intent.action) {
            AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> {
                LiveSubPassThroughService.activePackages[sessionId] = pkgName
                if (LiveSubPassThroughService.isRunning) {
                    val svc = Intent(context, LiveSubPassThroughService::class.java).apply {
                        action = LiveSubPassThroughService.ACTION_ATTACH_SESSION
                        putExtra(LiveSubPassThroughService.EXTRA_SESSION_ID, sessionId)
                        putExtra(LiveSubPassThroughService.EXTRA_PACKAGE_NAME, pkgName)
                    }
                    context.startService(svc)
                }
            }
            AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> {
                LiveSubPassThroughService.activePackages.remove(sessionId)
                if (LiveSubPassThroughService.isRunning) {
                    val svc = Intent(context, LiveSubPassThroughService::class.java).apply {
                        action = LiveSubPassThroughService.ACTION_DETACH_SESSION
                        putExtra(LiveSubPassThroughService.EXTRA_SESSION_ID, sessionId)
                    }
                    context.startService(svc)
                }
            }
        }
    }
}
