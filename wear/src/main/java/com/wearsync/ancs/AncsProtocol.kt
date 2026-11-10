package com.wearsync.ancs

import java.util.UUID

/**
 * Apple Notification Center Service (ANCS) constants.
 * Spec: https://developer.apple.com/library/archive/documentation/CoreBluetooth/Reference/AppleNotificationCenterServiceSpecification/
 */
object Ancs {
    val SERVICE: UUID = UUID.fromString("7905F431-B5CE-4E99-A40F-4B1E122D00D0")
    val NOTIFICATION_SOURCE: UUID = UUID.fromString("9FBF120D-6301-42D9-8C58-25E699A21DBD")
    val CONTROL_POINT: UUID = UUID.fromString("69D1D8F3-45E1-49A8-9821-9BBDFDAAD9D9")
    val DATA_SOURCE: UUID = UUID.fromString("22EAC6E9-24D6-4BB5-BE44-B36ACE7C7BFB")

    /** Client Characteristic Configuration Descriptor (standard). */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Apple Media Service — used in Phase 3. */
    val AMS_SERVICE: UUID = UUID.fromString("89D3502B-0F36-433A-8EF4-C502AD55F8DC")
}

object EventId {
    const val ADDED = 0
    const val MODIFIED = 1
    const val REMOVED = 2
}

object EventFlag {
    const val SILENT = 1 shl 0
    const val IMPORTANT = 1 shl 1
    const val PRE_EXISTING = 1 shl 2
    const val POSITIVE_ACTION = 1 shl 3
    const val NEGATIVE_ACTION = 1 shl 4
}

object Category {
    const val OTHER = 0
    const val INCOMING_CALL = 1
    const val MISSED_CALL = 2
    const val VOICEMAIL = 3
    const val SOCIAL = 4
    const val SCHEDULE = 5
    const val EMAIL = 6
    const val NEWS = 7
    const val HEALTH_AND_FITNESS = 8
    const val BUSINESS_AND_FINANCE = 9
    const val LOCATION = 10
    const val ENTERTAINMENT = 11

    /**
     * Not in Apple's spec. Observed on iOS (2026-11) while a call is connected: posted after
     * the call is answered, with a negative action labelled "End Call", removed when it ends.
     */
    const val ACTIVE_CALL = 12
}

object CommandId {
    const val GET_NOTIFICATION_ATTRIBUTES = 0
    const val GET_APP_ATTRIBUTES = 1
    const val PERFORM_NOTIFICATION_ACTION = 2
}

object NotificationAttributeId {
    const val APP_IDENTIFIER = 0
    const val TITLE = 1
    const val SUBTITLE = 2
    const val MESSAGE = 3
    const val MESSAGE_SIZE = 4
    const val DATE = 5
    const val POSITIVE_ACTION_LABEL = 6
    const val NEGATIVE_ACTION_LABEL = 7
}

object AppAttributeId {
    const val DISPLAY_NAME = 0
}

object ActionId {
    const val POSITIVE = 0
    const val NEGATIVE = 1
}
