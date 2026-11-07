package com.nexiolab.socialai

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.nexiolab.socialai.domain.SettingKeys
import com.nexiolab.socialai.domain.i18n.Msg
import com.nexiolab.socialai.domain.model.NoticeSeverity
import com.nexiolab.socialai.domain.repository.ActivityRepository
import com.nexiolab.socialai.domain.repository.SettingsRepository

/** Mirrors new in-app notices to system notifications when the owner allows them. */
class NotifyingActivity(
    private val context: Context,
    private val delegate: ActivityRepository,
    private val settings: SettingsRepository,
) : ActivityRepository by delegate {

    override suspend fun notice(dedupeKey: String, severity: NoticeSeverity, title: String, body: String): Boolean {
        val isNew = delegate.notice(dedupeKey, severity, title, body)
        if (isNew && settings.get(SettingKeys.NOTIFICATIONS) != "false") show(dedupeKey, title, body)
        return isNew
    }

    private fun show(key: String, title: String, body: String) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted) return
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, Msg.CHANNEL_UPDATES(), NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(key.hashCode(), notification)
    }

    private companion object {
        const val CHANNEL = "updates"
    }
}
