package com.wearsync.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.wearsync.R
import com.wearsync.ancs.ActionId
import com.wearsync.ancs.AncsNotification
import com.wearsync.service.ActiveCall
import com.wearsync.service.ActiveCallInfo
import com.wearsync.service.BridgeService
import com.wearsync.service.IncomingCalls
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The call screen. Follows one call through its stages:
 *  - Ringing: launched by the incoming-call notification's full-screen intent; Decline / Accept.
 *  - Connecting: after Accept, until iOS reports the call as connected.
 *  - On call: caller, timer, End Call (from the active-call notification, ANCS category 12).
 * Also opened directly in "On call" mode from the ongoing-call notification / watch-face icon.
 */
class CallActivity : ComponentActivity() {

    private var screen by mutableStateOf<Screen>(Screen.Waiting(""))
    private var watcher: Job? = null

    private val vibrator by lazy { getSystemService(VibratorManager::class.java).defaultVibrator }
    private val ringTimeout = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        setContent {
            MaterialTheme {
                when (val s = screen) {
                    is Screen.Ringing -> RingingScreen(s, onAccept = { answer(s) }, onDecline = { decline(s) })
                    is Screen.Waiting -> WaitingScreen(s.text)
                    is Screen.OnCall -> OnCallScreen(s.call, onEnd = { end(s.call) })
                }
            }
        }
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onDestroy() {
        stopRinging()
        super.onDestroy()
    }

    private fun handle(intent: Intent) {
        if (intent.action == ACTION_SHOW_ACTIVE) {
            ActiveCall.state.value?.let(::showOnCall) ?: finish()
            return
        }
        val uid = intent.getIntExtra(EXTRA_UID, NO_UID)
        if (uid == NO_UID || !IncomingCalls.isActive(uid)) {
            // The ringing already stopped before the screen came up; maybe it was answered.
            ActiveCall.state.value?.let(::showOnCall) ?: finish()
            return
        }
        val ringing = Screen.Ringing(
            uid = uid,
            caller = intent.getStringExtra(EXTRA_CALLER).orEmpty().ifBlank { "Unknown caller" },
            source = intent.getStringExtra(EXTRA_SOURCE).orEmpty(),
            acceptLabel = intent.getStringExtra(EXTRA_ACCEPT_LABEL),
            declineLabel = intent.getStringExtra(EXTRA_DECLINE_LABEL),
        )
        screen = ringing
        startRinging()
        watch {
            IncomingCalls.active.first { uid !in it }
            // Stopped ringing: answered (here or on the iPhone), declined, or hung up.
            stopRinging()
            awaitConnectedOrFinish()
        }
    }

    private fun answer(s: Screen.Ringing) {
        perform(s.uid, ActionId.POSITIVE)
        stopRinging()
        screen = Screen.Waiting("Connecting…")
        IncomingCalls.ended(s.uid) // the watcher in handle() then waits for the connected call
    }

    private fun decline(s: Screen.Ringing) {
        perform(s.uid, ActionId.NEGATIVE)
        IncomingCalls.ended(s.uid)
        finish()
    }

    private fun end(call: ActiveCallInfo) {
        perform(call.uid, ActionId.NEGATIVE)
        screen = Screen.Waiting("Ending call…")
        watch {
            withTimeoutOrNull(END_TIMEOUT_MS) { ActiveCall.state.first { it?.uid != call.uid } }
            finish()
        }
    }

    /** iOS posts the "call in progress" notification a moment after the call connects. */
    private suspend fun awaitConnectedOrFinish() {
        if (screen is Screen.Ringing) screen = Screen.Waiting("") // stopped ringing on the iPhone side
        val call = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { ActiveCall.state.filterNotNull().first() }
        if (call == null) finish() else showOnCall(call)
    }

    private fun showOnCall(call: ActiveCallInfo) {
        stopRinging()
        screen = Screen.OnCall(call)
        watch {
            ActiveCall.state.first { it?.uid != call.uid }
            finish() // call ended (on either side)
        }
    }

    private fun watch(block: suspend () -> Unit) {
        watcher?.cancel()
        watcher = lifecycleScope.launch { block() }
    }

    private fun perform(uid: Int, actionId: Int) {
        startService(
            Intent(this, BridgeService::class.java)
                .setAction(BridgeService.ACTION_PERFORM)
                .putExtra(BridgeService.EXTRA_UID, uid)
                .putExtra(BridgeService.EXTRA_ACTION_ID, actionId),
        )
    }

    private fun startRinging() {
        stopRinging()
        // buzz 600 ms, pause 800 ms, repeat from the start
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 800), 0))
        // Safety net in case the end of the call is never reported.
        ringTimeout.postDelayed(::stopRinging, MAX_RING_MS)
    }

    private fun stopRinging() {
        ringTimeout.removeCallbacksAndMessages(null)
        vibrator.cancel()
    }

    companion object {
        private const val NO_UID = Int.MIN_VALUE
        private const val MAX_RING_MS = 90_000L
        private const val CONNECT_TIMEOUT_MS = 4_000L
        private const val END_TIMEOUT_MS = 3_000L
        private const val ACTION_SHOW_ACTIVE = "com.wearsync.SHOW_ACTIVE_CALL"
        private const val EXTRA_UID = "uid"
        private const val EXTRA_CALLER = "caller"
        private const val EXTRA_SOURCE = "source"
        private const val EXTRA_ACCEPT_LABEL = "accept"
        private const val EXTRA_DECLINE_LABEL = "decline"

        /** Ringing screen for an incoming call. */
        fun intent(context: Context, n: AncsNotification): Intent =
            Intent(context, CallActivity::class.java)
                .setData(Uri.parse("wearsync://call/${n.uid}")) // distinct PendingIntent per call
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_UID, n.uid)
                .putExtra(EXTRA_CALLER, n.title)
                .putExtra(EXTRA_SOURCE, n.appLabel)
                .putExtra(EXTRA_ACCEPT_LABEL, n.positiveActionLabel)
                .putExtra(EXTRA_DECLINE_LABEL, n.negativeActionLabel)

        /** "On call" screen for the call in progress. */
        fun activeIntent(context: Context): Intent =
            Intent(context, CallActivity::class.java)
                .setAction(ACTION_SHOW_ACTIVE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/** What the call screen is showing. */
internal sealed interface Screen {
    data class Ringing(
        val uid: Int,
        val caller: String,
        val source: String,
        val acceptLabel: String?,
        val declineLabel: String?,
    ) : Screen

    data class Waiting(val text: String) : Screen
    data class OnCall(val call: ActiveCallInfo) : Screen
}

@Composable
private fun RingingScreen(r: Screen.Ringing, onAccept: () -> Unit, onDecline: () -> Unit) {
    CallLayout(header = "Incoming call", caller = r.caller, sub = r.source) {
        if (r.declineLabel != null) CallButton(R.drawable.ic_call_end, r.declineLabel, RED, onDecline)
        if (r.acceptLabel != null) CallButton(R.drawable.ic_call, r.acceptLabel, GREEN, onAccept)
    }
}

@Composable
private fun OnCallScreen(call: ActiveCallInfo, onEnd: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(call.uid) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val sub = listOf(call.source, formatDuration(now - call.startedAtMillis)).filter { it.isNotBlank() }.joinToString(" · ")
    CallLayout(header = "On call", caller = call.caller, sub = sub) {
        if (call.endLabel != null) CallButton(R.drawable.ic_call_end, call.endLabel, RED, onEnd)
    }
}

@Composable
private fun WaitingScreen(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.title3)
    }
}

@Composable
private fun CallLayout(header: String, caller: String, sub: String, buttons: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(header, style = MaterialTheme.typography.caption1, color = MaterialTheme.colors.onSurfaceVariant)
            Text(
                caller,
                style = MaterialTheme.typography.title2,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (sub.isNotBlank()) {
                Text(sub, style = MaterialTheme.typography.caption2, color = MaterialTheme.colors.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.padding(top = 14.dp)) {
                buttons()
            }
        }
    }
}

@Composable
private fun CallButton(@DrawableRes icon: Int, label: String, color: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(backgroundColor = color, contentColor = Color.White),
        modifier = Modifier.size(ButtonDefaults.LargeButtonSize),
    ) {
        Icon(painterResource(icon), contentDescription = label)
    }
}

private val RED = Color(0xFFD32F2F)
private val GREEN = Color(0xFF2E7D32)

private fun formatDuration(millis: Long): String {
    val s = (millis / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
