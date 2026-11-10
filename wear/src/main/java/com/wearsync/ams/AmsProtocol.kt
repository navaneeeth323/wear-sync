package com.wearsync.ams

import java.util.UUID

/**
 * Apple Media Service (AMS) constants.
 * Spec: https://developer.apple.com/library/archive/documentation/CoreBluetooth/Reference/AppleMediaService_Reference/
 */
object Ams {
    val SERVICE: UUID = UUID.fromString("89D3502B-0F36-433A-8EF4-C502AD55F8DC")

    /** Write a command ID to control playback; notifies with the list of supported commands. */
    val REMOTE_COMMAND: UUID = UUID.fromString("9B3C81D8-57B1-4A8A-B8DF-0E56F7CA51C2")

    /** Write [entity, attr, attr…] to subscribe; notifies with attribute changes. */
    val ENTITY_UPDATE: UUID = UUID.fromString("2F7CABCE-808D-411F-9A0C-BB92BA96C102")

    /** Used to read the full value of a truncated attribute (not used yet). */
    val ENTITY_ATTRIBUTE: UUID = UUID.fromString("C6B2F38C-23AB-46D8-A6AB-A3A870BBD5D7")
}

object RemoteCommand {
    const val PLAY = 0
    const val PAUSE = 1
    const val TOGGLE_PLAY_PAUSE = 2
    const val NEXT_TRACK = 3
    const val PREVIOUS_TRACK = 4
    const val VOLUME_UP = 5
    const val VOLUME_DOWN = 6
    const val ADVANCE_REPEAT_MODE = 7
    const val ADVANCE_SHUFFLE_MODE = 8
    const val SKIP_FORWARD = 9
    const val SKIP_BACKWARD = 10
    const val LIKE_TRACK = 11
    const val DISLIKE_TRACK = 12
    const val BOOKMARK_TRACK = 13
}

object EntityId {
    const val PLAYER = 0
    const val QUEUE = 1
    const val TRACK = 2
}

object PlayerAttribute {
    const val NAME = 0
    /** "PlaybackState,PlaybackRate,ElapsedTime", e.g. "1,1.0,42.5". */
    const val PLAYBACK_INFO = 1
    /** "0.0"–"1.0". */
    const val VOLUME = 2
}

object TrackAttribute {
    const val ARTIST = 0
    const val ALBUM = 1
    const val TITLE = 2
    /** Seconds, e.g. "215.3". */
    const val DURATION = 3
}

object PlaybackState {
    const val PAUSED = 0
    const val PLAYING = 1
    const val REWINDING = 2
    const val FAST_FORWARDING = 3
}

object EntityUpdateFlag {
    const val TRUNCATED = 1 shl 0
}
