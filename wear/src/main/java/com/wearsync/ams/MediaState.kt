package com.wearsync.ams

/** What's playing on the iPhone, as reported by AMS. Pure Kotlin so it can be unit tested. */
data class MediaState(
    val playerName: String = "",
    val playbackState: Int = PlaybackState.PAUSED,
    val rate: Float = 0f,
    /** Elapsed seconds as of [elapsedAtMillis]. */
    val elapsedSec: Double = 0.0,
    val elapsedAtMillis: Long = 0L,
    val volume: Float? = null,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationSec: Double? = null,
    /** Commands the current player accepts. Empty means "not reported yet". */
    val supportedCommands: Set<Int> = emptySet(),
) {
    val isPlaying get() = playbackState == PlaybackState.PLAYING
    val hasMedia get() = title.isNotEmpty() || playerName.isNotEmpty()

    fun supports(command: Int) = supportedCommands.isEmpty() || command in supportedCommands

    /** Current position, extrapolated while playing (AMS only reports changes, not ticks). */
    fun elapsedAt(nowMillis: Long): Double {
        if (playbackState == PlaybackState.PAUSED) return elapsedSec
        val pos = elapsedSec + rate * (nowMillis - elapsedAtMillis) / 1000.0
        val max = durationSec ?: Double.MAX_VALUE
        return pos.coerceIn(0.0, max)
    }

    fun apply(update: EntityUpdate, nowMillis: Long): MediaState = when (update.entityId) {
        EntityId.PLAYER -> when (update.attributeId) {
            PlayerAttribute.NAME -> copy(playerName = update.value)
            PlayerAttribute.PLAYBACK_INFO -> {
                val info = AmsCodec.parsePlaybackInfo(update.value)
                if (info == null) {
                    copy(playbackState = PlaybackState.PAUSED, rate = 0f, elapsedSec = 0.0, elapsedAtMillis = nowMillis)
                } else {
                    copy(playbackState = info.state, rate = info.rate, elapsedSec = info.elapsedSec, elapsedAtMillis = nowMillis)
                }
            }
            PlayerAttribute.VOLUME -> copy(volume = update.value.toFloatOrNull())
            else -> this
        }
        EntityId.TRACK -> when (update.attributeId) {
            TrackAttribute.ARTIST -> copy(artist = update.value)
            TrackAttribute.ALBUM -> copy(album = update.value)
            TrackAttribute.TITLE -> copy(title = update.value)
            TrackAttribute.DURATION -> copy(durationSec = update.value.toDoubleOrNull()?.takeIf { it > 0 })
            else -> this
        }
        else -> this
    }
}
