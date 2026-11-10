package com.wearsync.tile

import android.content.Context
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.Button
import androidx.wear.protolayout.material.ButtonColors
import androidx.wear.protolayout.material.ButtonDefaults
import androidx.wear.protolayout.material.Colors
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.protolayout.material.layouts.PrimaryLayout
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.wearsync.R
import com.wearsync.ams.MediaState
import com.wearsync.ams.RemoteCommand
import com.wearsync.ble.IPhoneLink
import com.wearsync.service.BridgeService
import com.wearsync.service.BridgeState

/**
 * "iPhone media" tile: current track plus previous / play-pause / next.
 *
 * Buttons use LoadAction: tapping one makes the system request a fresh tile with
 * `lastClickableId` set, and we send the matching AMS command from [onTileRequest].
 * [BridgeService] asks for a refresh whenever the iPhone reports a change.
 */
class MediaTileService : TileService() {

    override fun onTileRequest(request: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        handleClick(request.currentState.lastClickableId)
        val layout = layout(this, request.deviceConfiguration)
        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout))
            .build()
        return Futures.immediateFuture(tile)
    }

    /**
     * Clickable IDs look like "play_pause:17", where 17 is the render they came from. Each ID is
     * acted on once, so a refresh that still carries an old lastClickableId can't repeat a command.
     */
    private fun handleClick(clickableId: String) {
        if (clickableId.isEmpty() || clickableId == lastHandledClick) return
        lastHandledClick = clickableId
        val cmd = COMMANDS[clickableId.substringBefore(':')] ?: return
        val media = BridgeState.media.value
        BridgeService.sendMediaCommand(if (cmd == PLAY_PAUSE_PLACEHOLDER) playPauseCommand(media) else cmd)
    }

    override fun onTileResourcesRequest(
        request: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> {
        val resources = ResourceBuilders.Resources.Builder()
            .setVersion(RESOURCES_VERSION)
            .addIdToImageMapping(IMG_PLAY, image(R.drawable.ic_play))
            .addIdToImageMapping(IMG_PAUSE, image(R.drawable.ic_pause))
            .addIdToImageMapping(IMG_NEXT, image(R.drawable.ic_skip_next))
            .addIdToImageMapping(IMG_PREVIOUS, image(R.drawable.ic_skip_previous))
            .build()
        return Futures.immediateFuture(resources)
    }

    private fun layout(context: Context, device: DeviceParameters): LayoutElementBuilders.LayoutElement {
        val status = BridgeState.status.value
        val media = BridgeState.media.value
        val connected = status.running && status.link == IPhoneLink.State.READY

        val (heading, body) = when {
            !connected -> "WearSync" to "iPhone not connected"
            !media.hasMedia -> (media.playerName.ifEmpty { "iPhone" }) to "Nothing playing"
            else -> media.title.ifEmpty { media.playerName } to media.artist
        }

        val text = LayoutElementBuilders.Column.Builder()
            .addContent(
                Text.Builder(context, heading)
                    .setTypography(Typography.TYPOGRAPHY_TITLE3)
                    .setColor(androidx.wear.protolayout.ColorBuilders.argb(Colors.DEFAULT.onSurface))
                    .setMaxLines(2)
                    .build(),
            )
            .addContent(
                Text.Builder(context, body)
                    .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                    .setColor(androidx.wear.protolayout.ColorBuilders.argb(Colors.DEFAULT.onSurface))
                    .setMaxLines(1)
                    .build(),
            )
            .build()

        val builder = PrimaryLayout.Builder(device).setContent(text)
        if (connected && media.hasMedia) {
            builder.setPrimaryChipContent(controls(context, media))
        }
        return builder.build()
    }

    /** previous · play/pause · next, as one row in the layout's bottom slot. */
    private fun controls(context: Context, media: MediaState): LayoutElementBuilders.LayoutElement {
        val render = ++renderSeq
        val secondary = ButtonColors.secondaryButtonColors(Colors.DEFAULT)
        val primary = ButtonColors.primaryButtonColors(Colors.DEFAULT)
        return LayoutElementBuilders.Row.Builder()
            .addContent(button(context, "$ID_PREVIOUS:$render", IMG_PREVIOUS, SIDE_BUTTON_DP, secondary))
            .addContent(spacer())
            .addContent(
                button(
                    context,
                    "$ID_PLAY_PAUSE:$render",
                    if (media.isPlaying) IMG_PAUSE else IMG_PLAY,
                    ButtonDefaults.DEFAULT_SIZE.value,
                    primary,
                ),
            )
            .addContent(spacer())
            .addContent(button(context, "$ID_NEXT:$render", IMG_NEXT, SIDE_BUTTON_DP, secondary))
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .build()
    }

    private fun button(context: Context, id: String, image: String, sizeDp: Float, colors: ButtonColors) =
        Button.Builder(
            context,
            ModifiersBuilders.Clickable.Builder()
                .setId(id)
                .setOnClick(ActionBuilders.LoadAction.Builder().build())
                .build(),
        )
            .setIconContent(image)
            .setSize(dp(sizeDp))
            .setButtonColors(colors)
            .build()

    private fun spacer() = LayoutElementBuilders.Spacer.Builder().setWidth(dp(6f)).build()

    private fun image(resId: Int) = ResourceBuilders.ImageResource.Builder()
        .setAndroidResourceByResId(
            ResourceBuilders.AndroidImageResourceByResId.Builder().setResourceId(resId).build(),
        )
        .build()

    private fun playPauseCommand(media: MediaState) = when {
        media.isPlaying && media.supports(RemoteCommand.PAUSE) -> RemoteCommand.PAUSE
        !media.isPlaying && media.supports(RemoteCommand.PLAY) -> RemoteCommand.PLAY
        else -> RemoteCommand.TOGGLE_PLAY_PAUSE
    }

    private companion object {
        const val RESOURCES_VERSION = "1"
        const val SIDE_BUTTON_DP = 40f

        // Process-wide: the tile service instance can be recreated between requests.
        var renderSeq = System.currentTimeMillis() // unique across process restarts
        var lastHandledClick: String? = null

        const val ID_PREVIOUS = "previous"
        const val ID_PLAY_PAUSE = "play_pause"
        const val ID_NEXT = "next"
        const val PLAY_PAUSE_PLACEHOLDER = -1

        val COMMANDS = mapOf(
            ID_PREVIOUS to RemoteCommand.PREVIOUS_TRACK,
            ID_PLAY_PAUSE to PLAY_PAUSE_PLACEHOLDER,
            ID_NEXT to RemoteCommand.NEXT_TRACK,
        )

        const val IMG_PLAY = "play"
        const val IMG_PAUSE = "pause"
        const val IMG_NEXT = "next"
        const val IMG_PREVIOUS = "previous"
    }
}
