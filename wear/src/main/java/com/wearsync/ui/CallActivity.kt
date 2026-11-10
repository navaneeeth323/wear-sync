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
import com.wearsync.service.BridgeService
import com.wearsync.service.IncomingCalls
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Full-screen incoming-call UI, launched by the call notification's full-screen intent.
 * Closes itself when the call is answered/declined anywhere or the caller hangs up (ANCS
 * removes the notification → [IncomingCalls] drops the uid).
 */
class CallActivity : ComponentActivity() {

    private val vibrator by lazy { getSystemService(VibratorManager::class.java).defaultVibrator }
    private val timeout = Handler(Looper.getMainLooper())
    private var uid = NO_UID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        show(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        show(intent) // a second call, or the same call updated
    }

    override fun onDestroy() {
        stopRinging()
        super.onDestroy()
    }

    private fun show(intent: Intent) {
        uid = intent.getIntExtra(EXTRA_UID, NO_UID)
        if (uid == NO_UID || !IncomingCalls.isActive(uid)) {
            finish() // the call already ended before the screen came up
            return
        }
        val caller = intent.getStringExtra(EXTRA_CALLER).orEmpty().ifBlank { "Unknown caller" }
        val source = intent.getStringExtra(EXTRA_SOURCE).orEmpty()
        val acceptLabel = intent.getStringExtra(EXTRA_ACCEPT_LABEL)
        val declineLabel = intent.getStringExtra(EXTRA_DECLINE_LABEL)

        setContent {
            MaterialTheme {
                CallScreen(
                    caller = caller,
                    source = source,
                    acceptLabel = acceptLabel,
                    declineLabel = declineLabel,
                    onAccept = { act(ActionId.POSITIVE) },
                    onDecline = { act(ActionId.NEGATIVE) },
                )
            }
        }

        startRinging()
        val thisUid = uid
        lifecycleScope.launch {
            IncomingCalls.active.first { thisUid !in it }
            if (uid == thisUid) finish()
        }
    }

    private fun act(actionId: Int) {
        startService(
            Intent(this, BridgeService::class.java)
                .setAction(BridgeService.ACTION_PERFORM)
                .putExtra(BridgeService.EXTRA_UID, uid)
                .putExtra(BridgeService.EXTRA_ACTION_ID, actionId),
        )
        IncomingCalls.ended(uid)
        finish()
    }

    private fun startRinging() {
        stopRinging()
        // buzz 600 ms, pause 800 ms, repeat from the start
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 800), 0))
        // Safety net in case the end of the call is never reported.
        timeout.postDelayed(::stopRinging, MAX_RING_MS)
    }

    private fun stopRinging() {
        timeout.removeCallbacksAndMessages(null)
        vibrator.cancel()
    }

    companion object {
        private const val NO_UID = Int.MIN_VALUE
        private const val MAX_RING_MS = 90_000L
        private const val EXTRA_UID = "uid"
        private const val EXTRA_CALLER = "caller"
        private const val EXTRA_SOURCE = "source"
        private const val EXTRA_ACCEPT_LABEL = "accept"
        private const val EXTRA_DECLINE_LABEL = "decline"

        fun intent(context: Context, n: AncsNotification): Intent =
            Intent(context, CallActivity::class.java)
                .setData(Uri.parse("wearsync://call/${n.uid}")) // distinct PendingIntent per call
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_UID, n.uid)
                .putExtra(EXTRA_CALLER, n.title)
                .putExtra(EXTRA_SOURCE, n.appLabel)
                .putExtra(EXTRA_ACCEPT_LABEL, n.positiveActionLabel)
                .putExtra(EXTRA_DECLINE_LABEL, n.negativeActionLabel)
    }
}

@Composable
private fun CallScreen(
    caller: String,
    source: String,
    acceptLabel: String?,
    declineLabel: String?,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    Box(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Incoming call",
                style = MaterialTheme.typography.caption1,
                color = MaterialTheme.colors.onSurfaceVariant,
            )
            Text(
                caller,
                style = MaterialTheme.typography.title2,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (source.isNotBlank()) {
                Text(source, style = MaterialTheme.typography.caption2, color = MaterialTheme.colors.onSurfaceVariant)
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.padding(top = 14.dp),
            ) {
                // The iPhone only offers the actions it supports for this call.
                if (declineLabel != null) {
                    CallButton(R.drawable.ic_call_end, declineLabel, Color(0xFFD32F2F), onDecline)
                }
                if (acceptLabel != null) {
                    CallButton(R.drawable.ic_call, acceptLabel, Color(0xFF2E7D32), onAccept)
                }
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
