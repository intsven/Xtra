package com.github.andreyasadchy.xtra.ui.main

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs

object LiveNotificationHelper {

    const val GROUP_KEY = "com.github.andreyasadchy.xtra.LIVE_NOTIFICATIONS"

    fun showLiveNotification(context: Context, stream: Stream) {
        val channelId = context.getString(R.string.notification_live_channel_id)
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (notificationManager.getNotificationChannel(channelId) == null) {
                notificationManager.createNotificationChannel(
                    NotificationChannel(
                        channelId,
                        ContextCompat.getString(context, R.string.notification_live_channel_title),
                        NotificationManager.IMPORTANCE_DEFAULT
                    )
                )
            }
        }

        val notification = NotificationCompat.Builder(context, channelId).apply {
            setGroup(GROUP_KEY)
            setContentTitle(ContextCompat.getString(context, R.string.live_notification).format(
                if (stream.channelLogin != null && !stream.channelLogin.equals(stream.channelName, true)) {
                    when (context.prefs().getString(C.UI_NAME_DISPLAY, "0")) {
                        "0" -> "${stream.channelName}(${stream.channelLogin})"
                        "1" -> stream.channelName
                        else -> stream.channelLogin
                    }
                } else {
                    stream.channelName
                }
            ))
            setContentText(stream.title)
            setSmallIcon(R.drawable.notification_icon)
            setAutoCancel(true)
            setContentIntent(
                PendingIntent.getActivity(
                    context,
                    stream.channelId.hashCode(),
                    Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                        action = MainActivity.INTENT_LIVE_NOTIFICATION
                        putExtra(MainActivity.KEY_VIDEO, stream)
                    },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        }.build()

        notificationManager.notify(stream.channelId.hashCode(), notification)
    }
}
