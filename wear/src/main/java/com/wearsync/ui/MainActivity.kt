package com.wearsync.ui

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
