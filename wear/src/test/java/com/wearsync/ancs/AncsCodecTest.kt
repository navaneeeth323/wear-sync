package com.wearsync.ancs

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class AncsCodecTest {

    @Test
    fun parsesNotificationSource() {
        val packet = byteArrayOf(0, (EventFlag.IMPORTANT or EventFlag.POSITIVE_ACTION).toByte(), 4, 1, 0x2A, 0x01, 0, 0)
        val ev = AncsCodec.parseNotificationSource(packet)!!
        assertEquals(EventId.ADDED, ev.eventId)
        assertEquals(Category.SOCIAL, ev.categoryId)
        assertEquals(0x012A, ev.uid)
        assertTrue(ev.hasPositiveAction)
    }

    @Test
    fun encodesGetNotificationAttributes() {
        val cmd = AncsCodec.getNotificationAttributes(0x01020304)
        // command, uid (LE), then: app id, title+len, subtitle+len, message+len, date, +label, -label
        val expected = byteArrayOf(
            0, 4, 3, 2, 1,
            0,
            1, 64, 0,
            2, 64, 0,
            3, 0, 2, // 512 = 0x0200
            5, 6, 7,
        )
        assertArrayEquals(expected, cmd)
    }

    @Test
    fun encodesPerformAction() {
        assertArrayEquals(byteArrayOf(2, 7, 0, 0, 0, 1), AncsCodec.performAction(7, ActionId.NEGATIVE))
    }

    @Test
    fun parsesAttributesSplitAcrossPackets() {
        val response = notificationResponse(
            uid = 9,
            NotificationAttributeId.APP_IDENTIFIER to "com.apple.MobileSMS",
            NotificationAttributeId.TITLE to "Alice",
            NotificationAttributeId.SUBTITLE to "",
            NotificationAttributeId.MESSAGE to "Lunch? 🍜",
            NotificationAttributeId.DATE to "20261109T123000",
            NotificationAttributeId.POSITIVE_ACTION_LABEL to "",
            NotificationAttributeId.NEGATIVE_ACTION_LABEL to "Clear",
        )
        val expected = AncsCodec.NOTIFICATION_ATTRIBUTES.size

        // Anything short of the full response is "incomplete".
        assertNull(AncsCodec.parseNotificationAttributesResponse(response.copyOfRange(0, 20), expected))
        assertNull(AncsCodec.parseNotificationAttributesResponse(response.copyOfRange(0, response.size - 1), expected))

        val (uid, attrs) = AncsCodec.parseNotificationAttributesResponse(response, expected)!!
        assertEquals(9, uid)
        assertEquals("Alice", attrs[NotificationAttributeId.TITLE])
        assertEquals("Lunch? 🍜", attrs[NotificationAttributeId.MESSAGE])
        assertEquals("Clear", attrs[NotificationAttributeId.NEGATIVE_ACTION_LABEL])
    }

    @Test
    fun parsesAppAttributes() {
        val appId = "net.whatsapp.WhatsApp".toByteArray()
        val name = "WhatsApp".toByteArray()
        val response = byteArrayOf(1) + appId + byteArrayOf(0, 0, name.size.toByte(), 0) + name
        val (id, attrs) = AncsCodec.parseAppAttributesResponse(response, 1)!!
        assertEquals("net.whatsapp.WhatsApp", id)
        assertEquals("WhatsApp", attrs[AppAttributeId.DISPLAY_NAME])
    }

    @Test
    fun parsesDate() {
        assertEquals(
            1_794_227_400_000L, // 2026-11-09T12:30:00Z
            AncsCodec.parseDate("20261109T123000", ZoneOffset.UTC),
        )
        assertNull(AncsCodec.parseDate("garbage"))
    }

    private fun notificationResponse(uid: Int, vararg attrs: Pair<Int, String>): ByteArray {
        var out = byteArrayOf(0, uid.toByte(), (uid shr 8).toByte(), (uid shr 16).toByte(), (uid shr 24).toByte())
        for ((id, value) in attrs) {
            val bytes = value.toByteArray()
            out += byteArrayOf(id.toByte(), bytes.size.toByte(), (bytes.size shr 8).toByte()) + bytes
        }
        return out
    }
}
