package com.wearsync.ancs

/** A fully-resolved iPhone notification, ready to be shown on the watch. */
data class AncsNotification(
    val uid: Int,
    val eventId: Int,
    val flags: Int,
    val categoryId: Int,
    val appId: String,
    val appName: String?,
    val title: String?,
    val subtitle: String?,
    val message: String?,
    val dateMillis: Long?,
    val positiveActionLabel: String?,
    val negativeActionLabel: String?,
) {
    val appLabel: String
        get() = appName?.takeIf { it.isNotBlank() } ?: appId.ifBlank { "iPhone" }

    val body: String
        get() = listOfNotNull(subtitle, message).filter { it.isNotBlank() }.joinToString("\n")

    val isCall get() = categoryId == Category.INCOMING_CALL
    val isActiveCall get() = categoryId == Category.ACTIVE_CALL
    val isSilent get() = flags and EventFlag.SILENT != 0
    val isImportant get() = flags and EventFlag.IMPORTANT != 0
}
