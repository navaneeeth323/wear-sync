package com.wearsync.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.wearsync.apps.AppIcons
import com.wearsync.apps.AppMode
import com.wearsync.apps.AppRegistry
import com.wearsync.apps.KnownApp

/** Per-app notification settings: tap an app to cycle On → Silent → Off. */
class AppsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppRegistry.load(this)
        setContent { MaterialTheme { AppsScreen() } }
    }
}

@Composable
private fun AppsScreen() {
    val context = LocalContext.current
    val apps by AppRegistry.apps.collectAsStateWithLifecycle()
    val iconVersion by AppIcons.version.collectAsStateWithLifecycle()
    val listState = rememberScalingLazyListState()

    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
    ) {
        ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
            item { Text("Notification apps", style = MaterialTheme.typography.title3) }
            if (apps.isEmpty()) {
                item {
                    Text(
                        "iPhone apps appear here after they send a notification.",
                        style = MaterialTheme.typography.body2,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }
            items(apps, key = { it.appId }) { app ->
                key(iconVersion) { AppChip(app) { AppRegistry.setMode(context, app.appId, app.mode.next()) } }
            }
            item {
                Text(
                    "Tap to switch On → Silent → Off. Calls always ring.",
                    style = MaterialTheme.typography.caption2,
                    color = MaterialTheme.colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp, start = 12.dp, end = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun AppChip(app: KnownApp, onClick: () -> Unit) {
    val context = LocalContext.current
    val icon = AppIcons.iconFor(context, app.appId, app.name)
    Chip(
        onClick = onClick,
        label = { Text(app.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        secondaryLabel = {
            Text(
                when (app.mode) {
                    AppMode.ON -> "On"
                    AppMode.SILENT -> "Silent (no vibration)"
                    AppMode.OFF -> "Off (hidden)"
                },
            )
        },
        icon = {
            Image(
                bitmap = icon.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(ChipDefaults.LargeIconSize),
            )
        },
        colors = if (app.mode == AppMode.OFF) ChipDefaults.secondaryChipColors() else ChipDefaults.primaryChipColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}
