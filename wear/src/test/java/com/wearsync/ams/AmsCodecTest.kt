package com.wearsync.ams

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmsCodecTest {

    @Test
    fun encodesSubscriptions() {
        assertArrayEquals(byteArrayOf(2, 0, 1, 2, 3), AmsCodec.TRACK_SUBSCRIPTION)
        assertArrayEquals(byteArrayOf(0, 0, 1, 2), AmsCodec.PLAYER_SUBSCRIPTION)
        assertArrayEquals(byteArrayOf(3), AmsCodec.command(RemoteCommand.NEXT_TRACK))
    }

    @Test
    fun parsesEntityUpdate() {
        val packet = byteArrayOf(EntityId.TRACK.toByte(), TrackAttribute.TITLE.toByte(), 0) + "Café ☕".toByteArray()
        val update = AmsCodec.parseEntityUpdate(packet)!!
        assertEquals(EntityId.TRACK, update.entityId)
        assertEquals(TrackAttribute.TITLE, update.attributeId)
        assertFalse(update.truncated)
        assertEquals("Café ☕", update.value)

        val truncated = AmsCodec.parseEntityUpdate(byteArrayOf(2, 2, 1, 'x'.code.toByte()))!!
        assertTrue(truncated.truncated)
        assertNull(AmsCodec.parseEntityUpdate(byteArrayOf(0, 1)))
    }

    @Test
    fun parsesPlaybackInfo() {
        assertEquals(PlaybackInfo(PlaybackState.PLAYING, 1.0f, 42.5), AmsCodec.parsePlaybackInfo("1,1.0,42.5"))
        assertNull(AmsCodec.parsePlaybackInfo(""))
        assertNull(AmsCodec.parsePlaybackInfo("1,abc,3"))
    }

    @Test
    fun parsesSupportedCommands() {
        assertEquals(setOf(0, 1, 2, 3, 4), AmsCodec.parseSupportedCommands(byteArrayOf(0, 1, 2, 3, 4)))
    }

    @Test
    fun mediaStateTracksUpdatesAndExtrapolatesPosition() {
        var state = MediaState()
        state = state.apply(update(EntityId.TRACK, TrackAttribute.TITLE, "Song"), 0)
        state = state.apply(update(EntityId.TRACK, TrackAttribute.ARTIST, "Band"), 0)
        state = state.apply(update(EntityId.TRACK, TrackAttribute.DURATION, "200.0"), 0)
        state = state.apply(update(EntityId.PLAYER, PlayerAttribute.PLAYBACK_INFO, "1,1.0,10.0"), 1_000)

        assertTrue(state.hasMedia)
        assertTrue(state.isPlaying)
        assertEquals("Song", state.title)
        assertEquals(15.0, state.elapsedAt(6_000), 0.001) // 10s + 5s of playback
        assertEquals(200.0, state.elapsedAt(1_000_000), 0.001) // clamped to duration

        state = state.apply(update(EntityId.PLAYER, PlayerAttribute.PLAYBACK_INFO, "0,0.0,20.0"), 7_000)
        assertFalse(state.isPlaying)
        assertEquals(20.0, state.elapsedAt(60_000), 0.001) // paused: doesn't move
    }

    @Test
    fun emptySupportedCommandsMeansEverythingAllowed() {
        assertTrue(MediaState().supports(RemoteCommand.PLAY))
        val limited = MediaState(supportedCommands = setOf(RemoteCommand.TOGGLE_PLAY_PAUSE))
        assertFalse(limited.supports(RemoteCommand.NEXT_TRACK))
    }

    private fun update(entity: Int, attr: Int, value: String) = EntityUpdate(entity, attr, false, value)
}
