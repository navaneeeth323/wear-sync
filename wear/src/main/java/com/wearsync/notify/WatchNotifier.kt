package com.wearsync.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import com.wearsync.R
import com.wearsync.ancs.ActionId
import com.wearsync.ancs.AncsNotification
import com.wearsync.ancs.Category
import com.wearsync.service.BridgeService
import com.wearsync.ui.CallActivity
import com.wearsync.ui.MainActivity

/** Posts mirrored iPhone notifications, plus the foreground-service status notification. */
@SuppressLint("MissingPermission") // canPost() checks POST_NOTIFICATIONS
class WatchNotifier(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    init {
        manager.createNotificationChannelsCompat(
            listOf(
                channel(CH_STATUS, "Connection status", NotificationManagerCompat.IMPORTANCE_LOW),
                channel(CH_IPHONE, "iPhone notifications", NotificationManagerCompat.IMPORTANCE_HIGH),
                channel(CH_CALLS, "iPhone calls", NotificationManagerCompat.IMPORTANCE_HIGH),
            ),
        )
    }

    fun statusNotification(text: String): Notification =
        NotificationCompat.Builder(context, CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat_bridge)
            .setContentTitle("WearSync")
            .setContentText(text)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openAppIntent())
            .build()

    fun updateStatus(text: String) {
        if (canPost()) manager.notify(STATUS_ID, statusNotification(text))
    }

    fun post(n: AncsNotification) {
        if (!canPost()) return
        if (n.isCall) {
            postCall(n)
            return
        }
        val builder = NotificationCompat.Builder(context, if (n.isCall) CH_CALLS else CH_IPHONE)
            .setSmallIcon(R.drawable.ic_stat_bridge)
            .setContentTitle(n.title?.takeIf { it.isNotBlank() } ?: n.appLabel)
            .setContentText(n.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(n.body))
            .setSubText(n.appLabel)
            .setWhen(n.dateMillis ?: System.currentTimeMillis())
            .setShowWhen(true)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true) // a MODIFIED event re-posts the same uid without buzzing again
            .setSilent(n.isSilent)
            .setCategory(categoryFor(n.categoryId))
            .setPriority(if (n.isImportant || n.isCall) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)

        n.positiveActionLabel?.let { builder.addAction(0, it, actionIntent(n.uid, ActionId.POSITIVE)) }
        n.negativeActionLabel?.let { builder.addAction(0, it, actionIntent(n.uid, ActionId.NEGATIVE)) }

        manager.notify(TAG_IPHONE, n.uid, builder.build())
    }

    fun cancel(uid: Int) = manager.cancel(TAG_IPHONE, uid)

    /**
     * Incoming call: a CallStyle notification whose full-screen intent opens [CallActivity]
     * straight away (screen on, over the watch face) instead of waiting in the notification list.
     */
    private fun postCall(n: AncsNotification) {
        val caller = Person.Builder()
            .setName(n.title?.takeIf { it.isNotBlank() } ?: "Unknown caller")
            .setImportant(true)
            .build()
        val callScreen = PendingIntent.getActivity(
            context, 0, CallActivity.intent(context, n),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val accept = n.positiveActionLabel?.let { actionIntent(n.uid, ActionId.POSITIVE) }
        val decline = n.negativeActionLabel?.let { actionIntent(n.uid, ActionId.NEGATIVE) }

        fun base() = NotificationCompat.Builder(context, CH_CALLS)
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle(caller.name)
            .setContentText(n.appLabel)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(callScreen)
            .setFullScreenIntent(callScreen, true)

        val styled = base().also { b ->
            if (accept != null && decline != null) {
                b.setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, decline, accept))
            } else {
                n.positiveActionLabel?.let { b.addAction(R.drawable.ic_call, it, accept) }
                n.negativeActionLabel?.let { b.addAction(R.drawable.ic_call_end, it, decline) }
            }
        }
        try {
            manager.notify(TAG_IPHONE, n.uid, styled.build())
        } catch (e: IllegalArgumentException) {
            // CallStyle is rejected if the system won't honour the full-screen intent.
            Log.w("WearSync/Notify", "CallStyle rejected; posting a plain call notification", e)
            val plain = base()
            n.positiveActionLabel?.let { plain.addAction(R.drawable.ic_call, it, accept) }
            n.negativeActionLabel?.let { plain.addAction(R.drawable.ic_call_end, it, decline) }
            manager.notify(TAG_IPHONE, n.uid, plain.build())
        }
    }

    private fun actionIntent(uid: Int, actionId: Int): PendingIntent {
        val intent = Intent(context, BridgeService::class.java)
            .setAction(BridgeService.ACTION_PERFORM)
            // A unique data URI keeps each PendingIntent distinct.
            .setData(Uri.parse("wearsync://action/$uid/$actionId"))
            .putExtra(BridgeService.EXTRA_UID, uid)
            .putExtra(BridgeService.EXTRA_ACTION_ID, actionId)
        return PendingIntent.getService(
            context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun openAppIntent() = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
    )

    private fun canPost() = ContextCompat.checkSelfPermission(
        context, Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED

    private fun categoryFor(category: Int) = when (category) {
        Category.INCOMING_CALL -> NotificationCompat.CATEGORY_CALL
        Category.MISSED_CALL, Category.VOICEMAIL -> NotificationCompat.CATEGORY_MISSED_CALL
        Category.SOCIAL -> NotificationCompat.CATEGORY_MESSAGE
        Category.SCHEDULE -> NotificationCompat.CATEGORY_EVENT
        Category.EMAIL -> NotificationCompat.CATEGORY_EMAIL
        Category.NEWS -> NotificationCompat.CATEGORY_RECOMMENDATION
        Category.HEALTH_AND_FITNESS -> NotificationCompat.CATEGORY_WORKOUT
        Category.LOCATION -> NotificationCompat.CATEGORY_NAVIGATION
        else -> null
    }

    private fun channel(id: String, name: String, importance: Int) =
        NotificationChannelCompat.Builder(id, importance).setName(name).build()

    companion object {
        const val STATUS_ID = 1
        private const val TAG_IPHONE = "iphone"
        private const val CH_STATUS = "status"
        private const val CH_IPHONE = "iphone"
        private const val CH_CALLS = "iphone_calls"
    }
}
