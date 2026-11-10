package com.wearsync.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.wearsync.ams.MediaState
import com.wearsync.ble.IPhoneLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class BridgeStatus(
    val running: Boolean = false,
    val advertising: Boolean = false,
    val watchName: String? = null,
    val link: IPhoneLink.State = IPhoneLink.State.DISCONNECTED,
    val iphoneName: String? = null,
    val lastNotification: String? = null,
    val error: String? = null,
)

/** In-process status shared between [BridgeService] and the UI. */
object BridgeState {
    private val _status = MutableStateFlow(BridgeStatus())
    val status: StateFlow<BridgeStatus> = _status.asStateFlow()

    /** What's playing on the iPhone (AMS). */
    private val _media = MutableStateFlow(MediaState())
    val media: StateFlow<MediaState> = _media.asStateFlow()

    fun update(transform: (BridgeStatus) -> BridgeStatus) = _status.update(transform)
    fun updateMedia(transform: (MediaState) -> MediaState) = _media.update(transform)

    fun reset() {
        _status.value = BridgeStatus()
        _media.value = MediaState()
    }
}

/** iPhone calls currently ringing (ANCS uids), so the call screen can close when one ends. */
object IncomingCalls {
    private val _active = MutableStateFlow<Set<Int>>(emptySet())
    val active: StateFlow<Set<Int>> = _active.asStateFlow()

    fun isActive(uid: Int) = uid in _active.value
    fun started(uid: Int) = _active.update { it + uid }
    fun ended(uid: Int) = _active.update { it - uid }
    fun clear() { _active.value = emptySet() }
}

/** A connected iPhone call (ANCS category 12). */
data class ActiveCallInfo(
    val uid: Int,
    val caller: String,
    val source: String,
    val endLabel: String?,
    val startedAtMillis: Long,
)

/** The call currently in progress on the iPhone, if any. */
object ActiveCall {
    private val _state = MutableStateFlow<ActiveCallInfo?>(null)
    val state: StateFlow<ActiveCallInfo?> = _state.asStateFlow()

    fun started(info: ActiveCallInfo) { _state.value = info }
    fun ended(uid: Int) = _state.update { if (it?.uid == uid) null else it }
    fun clear() { _state.value = null }
}

object Prefs {
    private fun prefs(c: Context) = c.getSharedPreferences("wearsync", Context.MODE_PRIVATE)

    /** Whether the user switched the bridge on (used to restart it after a reboot). */
    fun isEnabled(c: Context) = prefs(c).getBoolean("enabled", false)
    fun setEnabled(c: Context, enabled: Boolean) = prefs(c).edit { putBoolean("enabled", enabled) }

    /** Bluetooth address of the iPhone we last reached ANCS on. */
    fun iphoneAddress(c: Context): String? = prefs(c).getString("iphone_address", null)
    fun setIphoneAddress(c: Context, address: String?) = prefs(c).edit { putString("iphone_address", address) }
}

object Permissions {
    val REQUIRED = arrayOf(
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    fun allGranted(c: Context) = REQUIRED.all {
        ContextCompat.checkSelfPermission(c, it) == PackageManager.PERMISSION_GRANTED
    }
}
