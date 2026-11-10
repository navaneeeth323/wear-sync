package com.wearsync.ams

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.app.PendingIntent
import android.os.Handler
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.util.Log
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media.VolumeProviderCompat
import com.wearsync.R
import com.wearsync.service.BridgeService

/**
 * Mirrors the iPhone's now-playing state into a local Android media session, plus a MediaStyle
 * notification. That's what makes Wear OS show its own media controls: the shortcut on the watch
 * face, the system media player, and bezel/crown volume, which goes to the iPhone through a
 * "remote" volume provider.
 *
 * Callbacks from the system media controls run on [handler] and become AMS commands via [send].
 */
@SuppressLint("MissingPermission") // POST_NOTIFICATIONS is checked before BridgeService starts.
class WatchMediaSession(
    private val context: Context,
    handler: Handler,
    private val send: (Int) -> Unit,
) {
    private val notifications = NotificationManagerCompat.from(context)

    /** The iPhone reports volume as 0.0–1.0; Android wants steps. */
    private val volume = object : VolumeProviderCompat(VOLUME_CONTROL_RELATIVE, VOLUME_STEPS, VOLUME_STEPS / 2) {
        override fun onAdjustVolume(direction: Int) {
            when {
                direction > 0 -> fromSystem("volume up", RemoteCommand.VOLUME_UP)
                direction < 0 -> fromSystem("volume down", RemoteCommand.VOLUME_DOWN)
            }
        }
    }

    private val session = MediaSessionCompat(context, "WearSync-iPhone").apply {
        setCallback(
            object : MediaSessionCompat.Callback() {
                override fun onPlay() = fromSystem("onPlay", RemoteCommand.PLAY)
                override fun onPause() = fromSystem("onPause", RemoteCommand.PAUSE)
                override fun onStop() = fromSystem("onStop", RemoteCommand.PAUSE)
                override fun onSkipToNext() = fromSystem("onSkipToNext", RemoteCommand.NEXT_TRACK)
                override fun onSkipToPrevious() = fromSystem("onSkipToPrevious", RemoteCommand.PREVIOUS_TRACK)
                override fun onPrepare() {
                    Log.i(TAG, "System media controls: onPrepare (ignored)")
                }
                override fun onPlayFromMediaId(mediaId: String?, extras: android.os.Bundle?) {
                    Log.i(TAG, "System media controls: onPlayFromMediaId($mediaId) (ignored)")
                }
                override fun onPlayFromUri(uri: Uri?, extras: android.os.Bundle?) {
                    Log.i(TAG, "System media controls: onPlayFromUri($uri) (ignored)")
                }
            },
            handler,
        )
        setPlaybackToRemote(volume)
    }

    init {
        notifications.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName("iPhone media")
                .build(),
        )
    }

    private fun fromSystem(what: String, command: Int) {
        Log.i(TAG, "System media controls: $what -> AMS command $command")
        send(command)
    }

    /** Call whenever the AMS state or the connection changes. */
    fun update(media: MediaState, connected: Boolean) {
        if (!connected || !media.hasMedia) {
            if (session.isActive) {
                session.isActive = false
                notifications.cancel(NOTIFICATION_ID)
            }
            return
        }

        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, media.title.ifEmpty { media.playerName })
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, media.artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, media.album)
        media.durationSec?.let { metadata.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, (it * 1000).toLong()) }
        session.setMetadata(metadata.build())

        // AMS timestamps are wall-clock; MediaSession wants elapsedRealtime.
        val updateTime = SystemClock.elapsedRealtime() - (System.currentTimeMillis() - media.elapsedAtMillis)
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions(media))
                .setState(
                    if (media.isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    (media.elapsedSec * 1000).toLong(),
                    if (media.isPlaying) media.rate else 0f,
                    updateTime,
                )
                .build(),
        )
        media.volume?.let { volume.currentVolume = (it * VOLUME_STEPS).toInt().coerceIn(0, VOLUME_STEPS) }
        session.isActive = true

        notifications.notify(NOTIFICATION_ID, notification(media))
    }

    fun release() {
        notifications.cancel(NOTIFICATION_ID)
        session.isActive = false
        session.release()
    }

    private fun notification(media: MediaState) =
        NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_bridge)
            .setContentTitle(media.title.ifEmpty { media.playerName })
            .setContentText(media.artist)
            .setSubText(media.playerName.ifEmpty { "iPhone" })
            .setOngoing(media.isPlaying)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .addAction(R.drawable.ic_skip_previous, "Previous", commandIntent(RemoteCommand.PREVIOUS_TRACK))
            .addAction(
                if (media.isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                if (media.isPlaying) "Pause" else "Play",
                commandIntent(if (media.isPlaying) RemoteCommand.PAUSE else RemoteCommand.PLAY),
            )
            .addAction(R.drawable.ic_skip_next, "Next", commandIntent(RemoteCommand.NEXT_TRACK))
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2),
            )
            .build()

    private fun commandIntent(command: Int): PendingIntent {
        val intent = Intent(context, BridgeService::class.java)
            .setAction(BridgeService.ACTION_MEDIA)
            .setData(Uri.parse("wearsync://media/$command"))
            .putExtra(BridgeService.EXTRA_MEDIA_COMMAND, command)
        return PendingIntent.getService(
            context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun actions(media: MediaState): Long {
        var actions = PlaybackStateCompat.ACTION_PLAY_PAUSE
        if (media.supports(RemoteCommand.PLAY)) actions = actions or PlaybackStateCompat.ACTION_PLAY
        if (media.supports(RemoteCommand.PAUSE)) actions = actions or PlaybackStateCompat.ACTION_PAUSE
        if (media.supports(RemoteCommand.NEXT_TRACK)) actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_NEXT
        if (media.supports(RemoteCommand.PREVIOUS_TRACK)) actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
        return actions
    }

    private companion object {
        const val TAG = "WearSync/Media"
        const val CHANNEL = "iphone_media"
        const val NOTIFICATION_ID = 2
        const val VOLUME_STEPS = 16
    }
}
