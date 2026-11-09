package com.wearsync.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts the bridge after the watch reboots, if the user had it switched on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (Prefs.isEnabled(context) && Permissions.allGranted(context)) {
            BridgeService.start(context)
        }
    }
}
