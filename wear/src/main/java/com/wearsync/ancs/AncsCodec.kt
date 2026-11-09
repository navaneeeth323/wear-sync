package com.wearsync.ancs

import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One 8-byte packet from the Notification Source characteristic. */
data class NotificationSourceEvent(
    val eventId: Int,
    val flags: Int,
    val categoryId: Int,
    val categoryCount: Int,
    val uid: Int,
) {
    val isPreExisting get() = flags and EventFlag.PRE_EXISTING != 0
    val hasPositiveAction get() = flags and EventFlag.POSITIVE_ACTION != 0
    val hasNegativeAction get() = flags and EventFlag.NEGATIVE_ACTION != 0
}

/**
 * Pure (Android-free) encoder/decoder for ANCS packets, so it can be unit tested on the JVM.
 * All multi-byte integers in ANCS are little-endian.
 */
object AncsCodec {
    const val MAX_TITLE = 64
    const val MAX_SUBTITLE = 64
    const val MAX_MESSAGE = 512

    /** Attributes requested for every notification, in request order. */
    val NOTIFICATION_ATTRIBUTES = listOf(
        NotificationAttributeId.APP_IDENTIFIER,
        NotificationAttributeId.TITLE,
        NotificationAttributeId.SUBTITLE,
        NotificationAttributeId.MESSAGE,
        NotificationAttributeId.DATE,
        NotificationAttributeId.POSITIVE_ACTION_LABEL,
        NotificationAttributeId.NEGATIVE_ACTION_LABEL,
    )

    private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    fun parseNotificationSource(b: ByteArray): NotificationSourceEvent? {
        if (b.size < 8) return null
        return NotificationSourceEvent(
            eventId = b[0].u(),
            flags = b[1].u(),
            categoryId = b[2].u(),
            categoryCount = b[3].u(),
            uid = readIntLE(b, 4),
        )
    }

    fun getNotificationAttributes(uid: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(CommandId.GET_NOTIFICATION_ATTRIBUTES)
        out.writeIntLE(uid)
        for (attr in NOTIFICATION_ATTRIBUTES) {
            out.write(attr)
            maxLengthFor(attr)?.let { out.writeShortLE(it) }
        }
        return out.toByteArray()
    }

    fun getAppAttributes(appId: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(CommandId.GET_APP_ATTRIBUTES)
        out.write(appId.toByteArray(Charsets.UTF_8))
        out.write(0)
        out.write(AppAttributeId.DISPLAY_NAME)
        return out.toByteArray()
    }

    fun performAction(uid: Int, actionId: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(CommandId.PERFORM_NOTIFICATION_ACTION)
        out.writeIntLE(uid)
        out.write(actionId)
        return out.toByteArray()
    }

    /**
     * Data Source responses can span several GATT notifications. Feed the accumulated bytes;
     * returns null until the full response has arrived.
     */
    fun parseNotificationAttributesResponse(data: ByteArray, expectedCount: Int): Pair<Int, Map<Int, String>>? {
        if (data.size < 5) return null
        val uid = readIntLE(data, 1)
        val attrs = parseAttributeList(data, 5, expectedCount) ?: return null
        return uid to attrs
    }

    fun parseAppAttributesResponse(data: ByteArray, expectedCount: Int): Pair<String, Map<Int, String>>? {
        var nul = -1
        for (i in 1 until data.size) if (data[i].toInt() == 0) { nul = i; break }
        if (nul < 0) return null
        val appId = String(data, 1, nul - 1, Charsets.UTF_8)
        val attrs = parseAttributeList(data, nul + 1, expectedCount) ?: return null
        return appId to attrs
    }

    /** ANCS dates are "yyyyMMdd'T'HHmmSS" in the iPhone's local time. */
    fun parseDate(value: String?, zone: ZoneId = ZoneId.systemDefault()): Long? = try {
        value?.takeIf { it.isNotBlank() }
            ?.let { LocalDateTime.parse(it, DATE_FORMAT).atZone(zone).toInstant().toEpochMilli() }
    } catch (_: Exception) {
        null
    }

    private fun parseAttributeList(data: ByteArray, start: Int, expectedCount: Int): Map<Int, String>? {
        var i = start
        val map = LinkedHashMap<Int, String>()
        repeat(expectedCount) {
            if (i + 3 > data.size) return null
            val id = data[i].u()
            val len = data[i + 1].u() or (data[i + 2].u() shl 8)
            i += 3
            if (i + len > data.size) return null
            map[id] = String(data, i, len, Charsets.UTF_8)
            i += len
        }
        return map
    }

    private fun maxLengthFor(attr: Int): Int? = when (attr) {
        NotificationAttributeId.TITLE -> MAX_TITLE
        NotificationAttributeId.SUBTITLE -> MAX_SUBTITLE
        NotificationAttributeId.MESSAGE -> MAX_MESSAGE
        else -> null
    }

    private fun Byte.u() = toInt() and 0xFF

    private fun readIntLE(b: ByteArray, off: Int) =
        b[off].u() or (b[off + 1].u() shl 8) or (b[off + 2].u() shl 16) or (b[off + 3].u() shl 24)

    private fun ByteArrayOutputStream.writeIntLE(v: Int) {
        write(v and 0xFF); write((v shr 8) and 0xFF); write((v shr 16) and 0xFF); write((v shr 24) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeShortLE(v: Int) {
        write(v and 0xFF); write((v shr 8) and 0xFF)
    }
}
