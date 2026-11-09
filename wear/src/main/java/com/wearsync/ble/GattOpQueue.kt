package com.wearsync.ble

import android.os.Handler
import android.util.Log

/**
 * Android's BluetoothGatt allows only one outstanding operation at a time; issuing a second
 * one before the first callback silently fails. This queue runs operations one after another.
 *
 * Every method must be called on [handler]'s thread.
 */
class GattOpQueue(private val handler: Handler) {

    private class Op(val name: String, val start: () -> Boolean)

    private val pending = ArrayDeque<Op>()
    private var current: Op? = null

    private val watchdog = Runnable {
        Log.w(TAG, "GATT op '${current?.name}' timed out")
        current = null
        next()
    }

    /** [start] returns true if the operation was started (its callback will follow). */
    fun enqueue(name: String, start: () -> Boolean) {
        pending.addLast(Op(name, start))
        if (current == null) next()
    }

    /** Call from the BluetoothGattCallback method matching the current op. */
    fun completed() {
        handler.removeCallbacks(watchdog)
        current = null
        next()
    }

    fun clear() {
        handler.removeCallbacks(watchdog)
        pending.clear()
        current = null
    }

    private fun next() {
        while (current == null) {
            val op = pending.removeFirstOrNull() ?: return
            current = op
            val started = try {
                op.start()
            } catch (e: SecurityException) {
                Log.e(TAG, "Missing Bluetooth permission for '${op.name}'", e)
                false
            }
            if (started) {
                handler.postDelayed(watchdog, OP_TIMEOUT_MS)
            } else {
                Log.w(TAG, "GATT op '${op.name}' failed to start")
                current = null
            }
        }
    }

    private companion object {
        const val TAG = "WearSync/GattQueue"
        const val OP_TIMEOUT_MS = 10_000L
    }
}
