package com.github.andreyasadchy.xtra.ui.main

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.github.andreyasadchy.xtra.model.ShownNotification
import com.github.andreyasadchy.xtra.model.ui.Stream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class XtraFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "XtraFCM"
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "FCM token: $token")
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        Log.d(TAG, "FCM message received: ${message.data}")

        val data = message.data
        if (data["type"] == "live_notification") {
            val stream = Stream(
                id = data["streamId"],
                channelId = data["channelId"],
                channelLogin = data["channelLogin"],
                channelName = data["channelName"],
                title = data["title"],
                gameName = data["gameName"],
                thumbnailURL = data["thumbnailURL"],
                viewerCount = data["viewerCount"]?.toIntOrNull(),
                createdAt = data["startedAt"],
            )

            LiveNotificationHelper.showLiveNotification(this, stream)

            val channelId = data["channelId"]
            val startedAt = data["startedAt"]?.let { com.github.andreyasadchy.xtra.util.TwitchApiHelper.parseIso8601DateUTC(it) } ?: System.currentTimeMillis()
            if (channelId != null) {
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val db = (application as com.github.andreyasadchy.xtra.XtraApp).xtraModule.database
                        db.shownNotifications().insertList(listOf(ShownNotification(channelId = channelId, startedAt = startedAt)))
                        Log.d(TAG, "Recorded shown notification for channel $channelId")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to record shown notification: ${e.message}")
                    }
                }
            }
        }
    }
}
