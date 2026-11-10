package com.wearsync.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.wearsync.R
import com.wearsync.ams.MediaState
import com.wearsync.ams.RemoteCommand
import com.wearsync.service.BridgeService
import kotlinx.coroutines.delay

/** Track info + transport and volume controls for whatever is playing on the iPhone. */
@Composable
fun NowPlaying(media: MediaState) {
    if (!media.hasMedia) {
        Text(
            "Nothing playing on iPhone",
            style = MaterialTheme.typography.caption1,
            textAlign = TextAlign.Center,
        )
        return
    }

    // AMS only reports changes, so tick locally to move the progress while playing.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(media.isPlaying, media.elapsedAtMillis) {
        while (media.isPlaying) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
    ) {
        Text(
            media.title.ifEmpty { media.playerName },
            style = MaterialTheme.typography.title3,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (media.artist.isNotEmpty()) {
            Text(
                media.artist,
                style = MaterialTheme.typography.caption1,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            progressText(media, now),
            style = MaterialTheme.typography.caption2,
            color = MaterialTheme.colors.onSurfaceVariant,
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 6.dp),
        ) {
            MediaButton(R.drawable.ic_skip_previous, "Previous", media, RemoteCommand.PREVIOUS_TRACK)
            MediaButton(
                if (media.isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                if (media.isPlaying) "Pause" else "Play",
                media,
                when {
                    media.isPlaying && media.supports(RemoteCommand.PAUSE) -> RemoteCommand.PAUSE
                    !media.isPlaying && media.supports(RemoteCommand.PLAY) -> RemoteCommand.PLAY
                    else -> RemoteCommand.TOGGLE_PLAY_PAUSE
                },
                size = ButtonDefaults.DefaultButtonSize,
                primary = true,
            )
            MediaButton(R.drawable.ic_skip_next, "Next", media, RemoteCommand.NEXT_TRACK)
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            MediaButton(R.drawable.ic_volume_down, "Volume down", media, RemoteCommand.VOLUME_DOWN)
            MediaButton(R.drawable.ic_volume_up, "Volume up", media, RemoteCommand.VOLUME_UP)
        }
    }
}

@Composable
private fun MediaButton(
    @DrawableRes icon: Int,
    label: String,
    media: MediaState,
    command: Int,
    size: Dp = ButtonDefaults.SmallButtonSize,
    primary: Boolean = false,
) {
    Button(
        onClick = { BridgeService.sendMediaCommand(command) },
        enabled = media.supports(command),
        colors = if (primary) ButtonDefaults.primaryButtonColors() else ButtonDefaults.secondaryButtonColors(),
        modifier = Modifier.size(size),
    ) {
        Icon(painterResource(icon), contentDescription = label)
    }
}

private fun progressText(media: MediaState, now: Long): String {
    val elapsed = formatTime(media.elapsedAt(now))
    val duration = media.durationSec ?: return elapsed
    return "$elapsed / ${formatTime(duration)}"
}

private fun formatTime(seconds: Double): String {
    val s = seconds.toLong().coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
