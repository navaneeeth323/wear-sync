package com.wearsync.service

import android.annotation.SuppressLint
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.wearsync.ancs.AncsClient
import com.wearsync.ancs.AncsNotification
import com.wearsync.ble.IPhoneLink
import com.wearsync.ble.PeripheralHost
import com.wearsync.notify.WatchNotifier

/**
 * Foreground service that owns the iPhone connection. All Bluetooth work runs on one
 * background thread ([handler]), so the BLE classes never need locking.
 *
 *   iPhone --(connects to)--> PeripheralHost --> IPhoneLink (GATT client) --> AncsClient --> WatchNotifier
 */
@SuppressLint("MissingPermission") // checked in onCreate
class BridgeService : Service(), PeripheralHost.Listener, IPhoneLink.Listener, AncsClient.Listener {

    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private lateinit var notifier: WatchNotifier
    private lateinit var host: PeripheralHost
    private lateinit var link: IPhoneLink
    private lateinit var ancs: AncsClient

    /** Centrals that turned out not to be iPhones (e.g. an Android phone), this session. */
    private val ignored = mutableSetOf<String>()

    /** Set while tearing down, so link callbacks don't restart advertising or reconnects. */
    @Volatile private var stopping = false

    override fun onCreate() {
        super.onCreate()
        notifier = WatchNotifier(this)
        if (!Permissions.allGranted(this)) {
            Log.e(TAG, "Missing permissions; not starting")
            stopSelf()
            return
        }
        ServiceCompat.startForeground(
            this,
            WatchNotifier.STATUS_ID,
            notifier.statusNotification("Starting…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )

        thread = HandlerThread("wearsync-ble").apply { start() }
        handler = Handler(thread.looper)
        handler.post {
            host = PeripheralHost(this, handler, this)
            link = IPhoneLink(this, handler, this)
            ancs = AncsClient(handler, link::writeControlPoint, this)
            BridgeState.update { it.copy(running = true, watchName = host.localName, error = null) }
            host.start()
            connectToIPhone()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Prefs.setEnabled(this, false)
                stopSelf()
            }
            ACTION_PERFORM -> {
                val uid = intent.getIntExtra(EXTRA_UID, -1)
                val actionId = intent.getIntExtra(EXTRA_ACTION_ID, -1)
                if (uid != -1 && actionId != -1 && ::handler.isInitialized) {
                    handler.post { ancs.performAction(uid, actionId) }
                    notifier.cancel(uid)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopping = true
        if (::thread.isInitialized) {
            handler.post {
                host.stop()
                link.close()
                ancs.reset()
            }
            thread.quitSafely() // runs the teardown above first
        }
        BridgeState.reset()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // --- PeripheralHost.Listener ---

    override fun onCentralConnected(device: BluetoothDevice) {
        when {
            device.address in ignored -> return
            link.state == IPhoneLink.State.READY && link.device?.address != device.address -> {
                Log.i(TAG, "Already linked to an iPhone; ignoring ${device.address}")
            }
            else -> link.connect(device)
        }
    }

    override fun onAdvertisingChanged(advertising: Boolean, error: String?) {
        BridgeState.update { it.copy(advertising = advertising, error = error) }
        refreshStatusNotification()
    }

    // --- IPhoneLink.Listener ---

    override fun onStateChanged(state: IPhoneLink.State, device: BluetoothDevice?) {
        if (stopping) return
        val name = try { device?.name } catch (_: SecurityException) { null }
        BridgeState.update { it.copy(link = state, iphoneName = name ?: it.iphoneName) }
        when (state) {
            IPhoneLink.State.READY -> {
                host.stopAdvertising()
                device?.let { Prefs.setIphoneAddress(this, it.address) }
            }
            IPhoneLink.State.DISCONNECTED -> {
                ancs.reset()
                host.startAdvertising() // lets a new, unbonded iPhone find us
                handler.postDelayed(::connectToIPhone, RECONNECT_DELAY_MS)
            }
            else -> Unit
        }
        refreshStatusNotification()
    }

    override fun onNotificationSource(value: ByteArray) = ancs.onNotificationSource(value)
    override fun onDataSource(value: ByteArray) = ancs.onDataSource(value)
    override fun onControlPointWriteFailed(status: Int) = ancs.onCommandFailed()

    override fun onNotAnIPhone(device: BluetoothDevice) {
        ignored += device.address
        if (Prefs.iphoneAddress(this) == device.address) Prefs.setIphoneAddress(this, null)
    }

    /**
     * iOS doesn't open an LE link to a bonded accessory by itself, so the watch opens it. Uses
     * the link if it's already up (e.g. the iPhone connected before we started), otherwise
     * leaves an autoConnect request pending that Android completes when the iPhone is in range.
     */
    private fun connectToIPhone() {
        if (stopping || link.state != IPhoneLink.State.DISCONNECTED) return
        val manager = getSystemService(BluetoothManager::class.java)
        val iphone = findIPhone(manager) ?: run {
            Log.i(TAG, "No bonded iPhone yet; waiting for one to connect to us")
            return
        }
        val linkUp = manager.getConnectedDevices(BluetoothProfile.GATT).any { it.address == iphone.address }
        link.connect(iphone, autoConnect = !linkUp)
    }

    private fun findIPhone(manager: BluetoothManager): BluetoothDevice? {
        val adapter = manager.adapter ?: return null
        Prefs.iphoneAddress(this)
            ?.takeIf { it !in ignored }
            ?.let { return adapter.getRemoteDevice(it) }
        // First run: pick a bonded iPhone. iPhones list an Apple-specific service UUID.
        return adapter.bondedDevices.firstOrNull { d ->
            d.address !in ignored && (
                d.uuids?.any { it == APPLE_DEVICE_UUID } == true ||
                    d.name?.contains("iPhone", ignoreCase = true) == true
                )
        }
    }

    // --- AncsClient.Listener ---

    override fun onNotification(notification: AncsNotification) {
        Log.d(TAG, "iPhone notification ${notification.uid} from ${notification.appLabel}")
        notifier.post(notification)
        BridgeState.update { it.copy(lastNotification = "${notification.appLabel}: ${notification.title.orEmpty()}") }
    }

    override fun onNotificationRemoved(uid: Int) = notifier.cancel(uid)

    private fun refreshStatusNotification() {
        val s = BridgeState.status.value
        notifier.updateStatus(
            when (s.link) {
                IPhoneLink.State.READY -> "Connected to ${s.iphoneName ?: "iPhone"}"
                IPhoneLink.State.PAIRING -> "Pairing…"
                IPhoneLink.State.CONNECTING, IPhoneLink.State.SUBSCRIBING -> "Connecting…"
                IPhoneLink.State.DISCONNECTED -> if (s.advertising) "Waiting for iPhone" else s.error ?: "Not connected"
            },
        )
    }

    companion object {
        private const val TAG = "WearSync/Service"
        const val ACTION_STOP = "com.wearsync.STOP"
        const val ACTION_PERFORM = "com.wearsync.PERFORM_ACTION"
        const val EXTRA_UID = "uid"
        const val EXTRA_ACTION_ID = "action_id"
        private const val RECONNECT_DELAY_MS = 2_000L
        private val APPLE_DEVICE_UUID = ParcelUuid.fromString("00000000-deca-fade-deca-deafdecacafe")

        fun start(context: Context) {
            Prefs.setEnabled(context, true)
            ContextCompat.startForegroundService(context, Intent(context, BridgeService::class.java))
        }

        fun stop(context: Context) {
            Prefs.setEnabled(context, false)
            context.stopService(Intent(context, BridgeService::class.java))
        }
    }
}
