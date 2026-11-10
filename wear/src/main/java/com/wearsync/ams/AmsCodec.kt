package com.wearsync.ams

/** One Entity Update notification: [EntityID, AttributeID, Flags, UTF-8 value…]. */
data class EntityUpdate(
    val entityId: Int,
    val attributeId: Int,
    val truncated: Boolean,
    val value: String,
)

data class PlaybackInfo(val state: Int, val rate: Float, val elapsedSec: Double)

/** Pure (Android-free) AMS encoder/decoder. */
object AmsCodec {

    /** What we subscribe to, one write per entity (AMS requires that). */
    val TRACK_SUBSCRIPTION = subscribe(
        EntityId.TRACK,
        TrackAttribute.ARTIST, TrackAttribute.ALBUM, TrackAttribute.TITLE, TrackAttribute.DURATION,
    )
    val PLAYER_SUBSCRIPTION = subscribe(
        EntityId.PLAYER,
        PlayerAttribute.NAME, PlayerAttribute.PLAYBACK_INFO, PlayerAttribute.VOLUME,
    )

    fun subscribe(entityId: Int, vararg attributeIds: Int): ByteArray =
        byteArrayOf(entityId.toByte()) + attributeIds.map { it.toByte() }.toByteArray()

    fun command(commandId: Int): ByteArray = byteArrayOf(commandId.toByte())

    fun parseEntityUpdate(b: ByteArray): EntityUpdate? {
        if (b.size < 3) return null
        return EntityUpdate(
            entityId = b[0].toInt() and 0xFF,
            attributeId = b[1].toInt() and 0xFF,
            truncated = (b[2].toInt() and EntityUpdateFlag.TRUNCATED) != 0,
            value = String(b, 3, b.size - 3, Charsets.UTF_8),
        )
    }

    fun parseSupportedCommands(b: ByteArray): Set<Int> = b.map { it.toInt() and 0xFF }.toSet()

    /** "1,1.0,42.5" → PlaybackInfo. Empty/garbled (no active player) → null. */
    fun parsePlaybackInfo(value: String): PlaybackInfo? {
        val parts = value.split(',')
        if (parts.size < 3) return null
        val state = parts[0].trim().toIntOrNull() ?: return null
        val rate = parts[1].trim().toFloatOrNull() ?: return null
        val elapsed = parts[2].trim().toDoubleOrNull() ?: return null
        return PlaybackInfo(state, rate, elapsed)
    }
}
