package com.wearsync.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.Handler
import android.os.ParcelUuid
import android.util.Log
import com.wearsync.ancs.Ancs
import java.util.UUID

/**
 * The watch's "accessory" side. It advertises asking for ANCS (a "service solicitation"),
 * which makes the watch appear under iPhone Settings -> Bluetooth. When the iPhone connects
 * (as the central), we report it so [IPhoneLink] can open a GATT client over the same link.
 *
 * Once bonded, iOS reconnects to the watch whenever it sees it advertising, so we keep
 * advertising whenever we're not connected.
 *
 * Listener callbacks are delivered on [handler]'s thread.
 */
@SuppressLint("MissingPermission") // Permissions are checked before BridgeService starts.
class PeripheralHost(
    private val context: Context,
    private val handler: Handler,
    private val listener: Listener,
) {
    interface Listener {
        fun onCentralConnected(device: BluetoothDevice)
        fun onAdvertisingChanged(advertising: Boolean, error: String?)
    }

    private val manager = context.getSystemService(BluetoothManager::class.java)
    private var server: BluetoothGattServer? = null
    private var advertising = false
    private var includeName = true

    val localName: String? get() = manager.adapter?.name

    fun start() {
        openServer()
        startAdvertising()
    }

    fun stop() {
        stopAdvertising()
        server?.close()
        server = null
    }

    fun startAdvertising() {
        if (advertising) return
        val advertiser = manager.adapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            listener.onAdvertisingChanged(false, "Bluetooth is off")
            return
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        // flags (3) + 128-bit solicitation (18) = 21 of the 31 bytes.
        val data = AdvertiseData.Builder()
            .addServiceSolicitationUuid(ParcelUuid(Ancs.SERVICE))
            .setIncludeDeviceName(false)
            .build()
        // The name goes into the scan response, so iOS has something to show in Settings.
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(includeName)
            .build()
        advertising = true
        advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback)
    }

    fun stopAdvertising() {
        if (!advertising) return
        advertising = false
        manager.adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        listener.onAdvertisingChanged(false, null)
    }

    private fun openServer() {
        if (server != null) return
        server = manager.openGattServer(context, serverCallback)?.also { s ->
            // A small service of our own so the iPhone finds something on our side. The
            // Phase 4 iOS companion app will use this service for its own data channel.
            val service = BluetoothGattService(BRIDGE_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            service.addCharacteristic(
                BluetoothGattCharacteristic(
                    BRIDGE_INFO,
                    BluetoothGattCharacteristic.PROPERTY_READ,
                    BluetoothGattCharacteristic.PERMISSION_READ,
                ),
            )
            s.addService(service)
        }
        if (server == null) Log.e(TAG, "openGattServer returned null")
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.i(TAG, "Advertising (name in scan response: $includeName)")
            handler.post { listener.onAdvertisingChanged(true, null) }
        }

        override fun onStartFailure(errorCode: Int) {
            handler.post {
                advertising = false
                when {
                    errorCode == AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> {
                        advertising = true
                        listener.onAdvertisingChanged(true, null)
                    }
                    errorCode == AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE && includeName -> {
                        // The watch's Bluetooth name is too long for the 31-byte scan response.
                        Log.w(TAG, "Name too long for scan response; advertising without it")
                        includeName = false
                        startAdvertising()
                    }
                    else -> {
                        Log.e(TAG, "Advertising failed: $errorCode")
                        listener.onAdvertisingChanged(false, "Advertising failed ($errorCode)")
                    }
                }
            }
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            Log.i(TAG, "Central ${device.address} state=$newState status=$status")
            // Note: this fires for *every* LE connection on the watch (including the Galaxy
            // Wearable phone, if any). IPhoneLink filters out devices without ANCS.
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                handler.post { listener.onCentralConnected(device) }
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val value = "WearSync/1".toByteArray()
            val slice = if (offset < value.size) value.copyOfRange(offset, value.size) else ByteArray(0)
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
        }
    }

    companion object {
        private const val TAG = "WearSync/Peripheral"
        val BRIDGE_SERVICE: UUID = UUID.fromString("5E4B1000-7A1C-4E2B-9C61-3F2D8A0B1C00")
        val BRIDGE_INFO: UUID = UUID.fromString("5E4B1001-7A1C-4E2B-9C61-3F2D8A0B1C00")
    }
}
