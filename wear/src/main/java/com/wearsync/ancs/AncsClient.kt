package com.wearsync.ancs

import android.os.Handler
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * ANCS protocol state machine. Turns Notification Source / Data Source traffic into
 * [AncsNotification]s. ANCS answers one Control Point command at a time, so requests are
 * sent one by one, each waiting for its full Data Source response.
 *
 * Every method must be called on [handler]'s thread.
 */
class AncsClient(
    private val handler: Handler,
    private val sendCommand: (ByteArray) -> Unit,
    private val listener: Listener,
    /** Notifications already on the iPhone when we connect. Off by default to avoid a flood. */
    private val includePreExisting: Boolean = false,
) {
    interface Listener {
        fun onNotification(notification: AncsNotification)
        fun onNotificationRemoved(uid: Int)
    }

    private sealed interface Request {
        data class NotificationAttrs(val event: NotificationSourceEvent) : Request
        data class AppAttrs(val appId: String) : Request
    }

    private val queue = ArrayDeque<Request>()
    private var inFlight: Request? = null
    private val buffer = ByteArrayOutputStream()

    /** Bundle ID -> display name ("net.whatsapp.WhatsApp" -> "WhatsApp"). Kept across reconnects. */
    private val appNames = HashMap<String, String>()
    private val waitingForAppName = HashMap<String, MutableList<AncsNotification>>()

    private val timeout = Runnable {
        Log.w(TAG, "No complete response for $inFlight; skipping")
        abandonInFlight()
    }

    fun onNotificationSource(value: ByteArray) {
        val event = AncsCodec.parseNotificationSource(value) ?: return
        when (event.eventId) {
            EventId.ADDED, EventId.MODIFIED -> {
                if (event.isPreExisting && !includePreExisting) return
                enqueue(Request.NotificationAttrs(event))
            }
            EventId.REMOVED -> {
                queue.removeAll { it is Request.NotificationAttrs && it.event.uid == event.uid }
                listener.onNotificationRemoved(event.uid)
            }
        }
    }

    fun onDataSource(value: ByteArray) {
        val request = inFlight ?: run {
            Log.w(TAG, "Unexpected Data Source packet (${value.size} bytes)")
            return
        }
        buffer.write(value)
        val bytes = buffer.toByteArray()

        when (request) {
            is Request.NotificationAttrs -> {
                val (_, attrs) = AncsCodec.parseNotificationAttributesResponse(
                    bytes, AncsCodec.NOTIFICATION_ATTRIBUTES.size,
                ) ?: return // wait for more bytes
                finishInFlight()
                onAttributes(request.event, attrs)
            }
            is Request.AppAttrs -> {
                val (_, attrs) = AncsCodec.parseAppAttributesResponse(bytes, 1) ?: return
                finishInFlight()
                val name = attrs[AppAttributeId.DISPLAY_NAME].orEmpty().ifBlank { request.appId }
                appNames[request.appId] = name
                releaseWaiting(request.appId, name)
            }
        }
        pump()
    }

    /** The iPhone rejected a Control Point write (e.g. the notification was already removed). */
    fun onCommandFailed() {
        if (inFlight != null) abandonInFlight()
    }

    fun performAction(uid: Int, actionId: Int) {
        sendCommand(AncsCodec.performAction(uid, actionId))
    }

    /** Call when the link drops. */
    fun reset() {
        handler.removeCallbacks(timeout)
        queue.clear()
        inFlight = null
        buffer.reset()
        waitingForAppName.clear()
    }

    private fun onAttributes(event: NotificationSourceEvent, attrs: Map<Int, String>) {
        val appId = attrs[NotificationAttributeId.APP_IDENTIFIER].orEmpty()
        val notification = AncsNotification(
            uid = event.uid,
            eventId = event.eventId,
            flags = event.flags,
            categoryId = event.categoryId,
            appId = appId,
            appName = appNames[appId],
            title = attrs[NotificationAttributeId.TITLE],
            subtitle = attrs[NotificationAttributeId.SUBTITLE],
            message = attrs[NotificationAttributeId.MESSAGE],
            dateMillis = AncsCodec.parseDate(attrs[NotificationAttributeId.DATE]),
            positiveActionLabel = attrs[NotificationAttributeId.POSITIVE_ACTION_LABEL]
                ?.takeIf { event.hasPositiveAction && it.isNotBlank() },
            negativeActionLabel = attrs[NotificationAttributeId.NEGATIVE_ACTION_LABEL]
                ?.takeIf { event.hasNegativeAction && it.isNotBlank() },
        )

        if (notification.appName != null || appId.isEmpty()) {
            listener.onNotification(notification)
            return
        }
        // Look up the app's display name first, then post.
        val waiting = waitingForAppName[appId]
        if (waiting != null) {
            waiting += notification
        } else {
            waitingForAppName[appId] = mutableListOf(notification)
            enqueue(Request.AppAttrs(appId))
        }
    }

    private fun releaseWaiting(appId: String, name: String?) {
        waitingForAppName.remove(appId)?.forEach { listener.onNotification(it.copy(appName = name)) }
    }

    private fun enqueue(request: Request) {
        queue.addLast(request)
        pump()
    }

    private fun pump() {
        if (inFlight != null) return
        val next = queue.removeFirstOrNull() ?: return
        inFlight = next
        buffer.reset()
        handler.postDelayed(timeout, RESPONSE_TIMEOUT_MS)
        sendCommand(
            when (next) {
                is Request.NotificationAttrs -> AncsCodec.getNotificationAttributes(next.event.uid)
                is Request.AppAttrs -> AncsCodec.getAppAttributes(next.appId)
            },
        )
    }

    private fun finishInFlight() {
        handler.removeCallbacks(timeout)
        inFlight = null
        buffer.reset()
    }

    private fun abandonInFlight() {
        val request = inFlight
        finishInFlight()
        // Don't lose notifications just because the app-name lookup failed.
        if (request is Request.AppAttrs) releaseWaiting(request.appId, null)
        pump()
    }

    private companion object {
        const val TAG = "WearSync/ANCS"
        const val RESPONSE_TIMEOUT_MS = 5_000L
    }
}
