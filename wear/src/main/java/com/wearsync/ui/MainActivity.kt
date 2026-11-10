package com.wearsync.ui

import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.wearsync.ble.IPhoneLink
import com.wearsync.service.BridgeService
import com.wearsync.service.BridgeState
import com.wearsync.service.BridgeStatus
import com.wearsync.service.Permissions

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { BridgeScreen() } }
    }
}

@Composable
private fun BridgeScreen() {
    val context = LocalContext.current
    val status by BridgeState.status.collectAsStateWithLifecycle()
    val media by BridgeState.media.collectAsStateWithLifecycle()
    // Re-checked on resume, so the button disappears after granting it in Settings.
    var canShowCallScreen by remember { mutableStateOf(canUseFullScreenIntent(context)) }
    LifecycleResumeEffect(Unit) {
        canShowCallScreen = canUseFullScreenIntent(context)
        onPauseOrDispose { }
    }
    val listState = rememberScalingLazyListState()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        if (Permissions.allGranted(context)) BridgeService.start(context)
    }

    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
    ) {
        ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
            item {
                Text("WearSync", style = MaterialTheme.typography.title3)
            }
            item {
                Text(
                    text = describe(status),
                    style = MaterialTheme.typography.body2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
            if (status.running && status.link == IPhoneLink.State.READY) {
                item { NowPlaying(media) }
            }
            item {
                if (status.running) {
                    Chip(
                        label = { Text("Stop") },
                        onClick = { BridgeService.stop(context) },
                        colors = ChipDefaults.secondaryChipColors(),
                    )
                } else {
                    Chip(
                        label = { Text("Start") },
                        onClick = { start(context) { permissionLauncher.launch(Permissions.REQUIRED) } },
                    )
                }
            }
            if (status.running && !canShowCallScreen) {
                item {
                    Chip(
                        label = { Text("Allow call screen") },
                        secondaryLabel = { Text("Show iPhone calls full-screen") },
                        onClick = { openFullScreenIntentSettings(context) },
                        colors = ChipDefaults.secondaryChipColors(),
                    )
                }
            }
            status.lastNotification?.let { last ->
                item {
                    Text(
                        text = "Last: $last",
                        style = MaterialTheme.typography.caption2,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                    )
                }
            }
        }
    }
}

private fun canUseFullScreenIntent(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
        context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

private fun openFullScreenIntentSettings(context: Context) {
    val pkg = Uri.parse("package:${context.packageName}")
    try {
        context.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg))
    } catch (_: ActivityNotFoundException) {
        // Some watches don't have that screen; fall back to the app's settings page.
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg))
    }
}

private fun start(context: Context, requestPermissions: () -> Unit) {
    if (Permissions.allGranted(context)) BridgeService.start(context) else requestPermissions()
}

private fun describe(s: BridgeStatus): String = when {
    !s.running -> "Stopped"
    s.link == IPhoneLink.State.READY -> "Connected to ${s.iphoneName ?: "iPhone"}"
    s.link == IPhoneLink.State.PAIRING -> "Confirm pairing on your iPhone"
    s.link == IPhoneLink.State.SUBSCRIBING -> "Allow notifications on your iPhone"
    s.link == IPhoneLink.State.CONNECTING -> "Connecting…"
    s.error != null -> s.error
    s.advertising -> "On iPhone: Settings → Bluetooth → tap “${s.watchName ?: "this watch"}”"
    else -> "Starting…"
}
