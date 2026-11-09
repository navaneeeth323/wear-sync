package com.wearsync.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.content.edit
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

    fun update(transform: (BridgeStatus) -> BridgeStatus) = _status.update(transform)
    fun reset() { _status.value = BridgeStatus() }
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
