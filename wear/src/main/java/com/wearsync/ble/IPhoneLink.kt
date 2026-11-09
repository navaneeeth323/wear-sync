package com.wearsync.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.util.Log
import androidx.core.content.ContextCompat
import com.wearsync.ancs.Ancs

/**
 * GATT *client* connection to the iPhone: finds ANCS, gets the watch bonded (ANCS needs an
 * encrypted link), subscribes to Data Source + Notification Source, and writes Control Point
 * commands.
 *
 * Every method and every listener callback runs on [handler]'s thread.
 */
@SuppressLint("MissingPermission") // Permissions are checked before BridgeService starts.
class IPhoneLink(
    private val context: Context,
    private val handler: Handler,
    private val listener: Listener,
) {
    enum class State { DISCONNECTED, CONNECTING, PAIRING, SUBSCRIBING, READY }

    interface Listener {
        fun onStateChanged(state: State, device: BluetoothDevice?)
        fun onNotificationSource(value: ByteArray)
        fun onDataSource(value: ByteArray)
        fun onControlPointWriteFailed(status: Int)
        /** The connected central has no ANCS (e.g. an Android phone). */
        fun onNotAnIPhone(device: BluetoothDevice)
    }

    var state = State.DISCONNECTED
        private set
    var device: BluetoothDevice? = null
        private set

    private var gatt: BluetoothGatt? = null
    private val ops = GattOpQueue(handler)
    private var controlPoint: BluetoothGattCharacteristic? = null
    private var discoveryRetries = 0

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            if (d == null || (d.address != device?.address && state != State.PAIRING)) return
            onBondStateChanged(intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR))
        }
    }

    init {
        ContextCompat.registerReceiver(
            context,
            bondReceiver,
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            null,
            handler,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    /**
     * Open a GATT client to [target]. With [autoConnect] Android keeps a background connection
     * request open and connects whenever the iPhone is in range. This is how we reach a bonded
     * iPhone, because iOS won't open an LE link to us by itself.
     */
    fun connect(target: BluetoothDevice, autoConnect: Boolean = false) {
        if (gatt != null) {
            if (target.address == device?.address) return
            closeGatt()
        }
        device = target
        discoveryRetries = 0
        Log.i(TAG, "Connecting to ${target.address} (autoConnect=$autoConnect)")
        setState(State.CONNECTING)
        gatt = target.connectGatt(
            context, autoConnect, callback, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, handler,
        )
    }

    fun disconnect() {
        closeGatt()
        setState(State.DISCONNECTED)
    }

    /** Release everything; the object can't be used afterwards. */
    fun close() {
        disconnect()
        try {
            context.unregisterReceiver(bondReceiver)
        } catch (_: IllegalArgumentException) {
            // Already unregistered; close() is safe to call more than once.
        }
    }

    fun writeControlPoint(bytes: ByteArray) {
        val g = gatt
        val cp = controlPoint
        if (g == null || cp == null || state != State.READY) {
            handler.post { listener.onControlPointWriteFailed(-1) }
            return
        }
        ops.enqueue("control point") {
            val ok = g.writeCharacteristic(cp, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothStatusCodes.SUCCESS
            if (!ok) handler.post { listener.onControlPointWriteFailed(-2) }
            ok
        }
    }

    private fun closeGatt() {
        ops.clear()
        controlPoint = null
        gatt?.let {
            it.disconnect()
            it.close()
        }
        gatt = null
    }

    private fun setState(newState: State) {
        if (state == newState) return
        Log.i(TAG, "$state -> $newState")
        state = newState
        listener.onStateChanged(newState, device)
    }

    private fun discover(g: BluetoothGatt) = ops.enqueue("discover services") { g.discoverServices() }

    private fun handleServices(g: BluetoothGatt, status: Int) {
        val ancs = g.getService(Ancs.SERVICE)
        if (status != BluetoothGatt.GATT_SUCCESS || ancs == null) {
            // iOS may hide ANCS until the link is encrypted, so pair first.
            if (g.device.bondState != BluetoothDevice.BOND_BONDED) {
                startPairing(g.device)
                return
            }
            if (discoveryRetries++ < MAX_DISCOVERY_RETRIES) {
                handler.postDelayed({ gatt?.let(::discover) }, 1_500)
                return
            }
            Log.w(TAG, "${g.device.address} has no ANCS; ignoring it")
            val notIPhone = g.device
            disconnect()
            listener.onNotAnIPhone(notIPhone)
            return
        }

        val ns = ancs.getCharacteristic(Ancs.NOTIFICATION_SOURCE)
        val ds = ancs.getCharacteristic(Ancs.DATA_SOURCE)
        val cp = ancs.getCharacteristic(Ancs.CONTROL_POINT)
        if (ns == null || ds == null || cp == null) {
            Log.e(TAG, "ANCS is missing characteristics: ns=$ns ds=$ds cp=$cp")
            return
        }
        controlPoint = cp
        setState(State.SUBSCRIBING)
        // Apple recommends subscribing to Data Source before Notification Source.
        enableNotifications(g, ds)
        enableNotifications(g, ns)
    }

    private fun enableNotifications(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
        ops.enqueue("subscribe ${c.uuid}") {
            g.setCharacteristicNotification(c, true)
            val cccd = c.getDescriptor(Ancs.CCCD) ?: return@enqueue false
            g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
        }
    }

    private fun startPairing(d: BluetoothDevice) {
        setState(State.PAIRING)
        ops.clear()
        if (d.bondState == BluetoothDevice.BOND_NONE) {
            Log.i(TAG, "Creating bond with ${d.address}")
            d.createBond()
        }
        // BOND_BONDING: Android started it already; wait for the broadcast.
    }

    private fun onBondStateChanged(bondState: Int) {
        when (bondState) {
            BluetoothDevice.BOND_BONDED -> {
                Log.i(TAG, "Bonded")
                if (state == State.PAIRING) {
                    discoveryRetries = 0
                    gatt?.let(::discover)
                }
            }
            BluetoothDevice.BOND_NONE -> if (state == State.PAIRING) {
                Log.w(TAG, "Pairing failed or was declined on the iPhone")
                disconnect()
            }
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (g !== gatt) return
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "GATT client connected to ${g.device.address}")
                    ops.clear()
                    ops.enqueue("request MTU") { g.requestMtu(MTU) }
                    discover(g)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "GATT client disconnected (status=$status)")
                    closeGatt()
                    setState(State.DISCONNECTED)
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "MTU=$mtu status=$status")
            ops.completed()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            ops.completed()
            handleServices(g, status)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            ops.completed()
            when {
                status == BluetoothGatt.GATT_SUCCESS -> {
                    if (d.characteristic.uuid == Ancs.NOTIFICATION_SOURCE) setState(State.READY)
                }
                status in AUTH_ERRORS -> startPairing(g.device)
                else -> Log.e(TAG, "Subscribing to ${d.characteristic.uuid} failed: $status")
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            ops.completed()
            if (status != BluetoothGatt.GATT_SUCCESS) {
                // ANCS errors: 0xA0 unknown command, 0xA1 invalid command, 0xA2 invalid parameter,
                // 0xA3 action failed.
                Log.w(TAG, "Control Point write failed: 0x${status.toString(16)}")
                listener.onControlPointWriteFailed(status)
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            when (c.uuid) {
                Ancs.NOTIFICATION_SOURCE -> listener.onNotificationSource(value)
                Ancs.DATA_SOURCE -> listener.onDataSource(value)
            }
        }
    }

    private companion object {
        const val TAG = "WearSync/Link"
        const val MTU = 185
        const val MAX_DISCOVERY_RETRIES = 3

        /** insufficient authentication, insufficient encryption, GATT_AUTH_FAIL (0x89). */
        val AUTH_ERRORS = setOf(5, 15, 137)
    }
}
