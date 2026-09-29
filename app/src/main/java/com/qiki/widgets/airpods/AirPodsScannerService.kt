package com.qiki.widgets.airpods

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.appwidget.AppWidgetManager
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.qiki.widgets.R
import com.qiki.widgets.widget.SampleWidgetProvider

/**
 * Experimental AirPods BLE adapter. Apple does not publish this broadcast format;
 * the parser is intentionally isolated so it can be replaced per model or OS.
 */
class AirPodsScannerService : Service() {
    private var scanner: BluetoothLeScanner? = null
    private val store by lazy { AirPodsBatteryStore(this) }
    private val recentBeacons = ArrayList<ScanResult>()
    private var pendingBattery: AirPodsBattery? = null
    private var pendingHits = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForeground(NOTIFICATION_ID, notification())
        }
        startScan()
    }

    override fun onDestroy() {
        scanner?.stopScan(scanCallback)
        scanner = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REFRESH) {
            scanner?.stopScan(scanCallback)
            recentBeacons.clear()
            pendingBattery = null
            pendingHits = 0
            startScan()
        }
        return START_STICKY
    }

    private fun startScan() {
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (!adapter.isEnabled) {
            Log.w(TAG, "Bluetooth is disabled")
            return
        }
        scanner = adapter.bluetoothLeScanner ?: run {
            Log.w(TAG, "BLE scanner unavailable")
            return
        }
        Log.d(TAG, "starting BLE scan")
        scanner?.startScan(
            null,
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            scanCallback,
        )
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed error=$errorCode")
        }

        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val rawManufacturerData = result.scanRecord
                ?.getManufacturerSpecificData(APPLE_COMPANY_ID)
                ?: return
            val manufacturerData = normalizePayload(rawManufacturerData) ?: return

            // AirPods broadcasts begin with 0x07, 0x19. Other Apple BLE beacons
            // must not be interpreted as battery packets.
            if (manufacturerData[0].toInt() and 0xFF != 0x07 ||
                manufacturerData[1].toInt() and 0xFF != 0x19
            ) return
            val modelId = (u8(manufacturerData[3]) shl 8) or u8(manufacturerData[4])
            if (modelId !in AIRPODS_MODEL_IDS) return

            val now = SystemClock.elapsedRealtimeNanos()
            recentBeacons.removeAll { now - it.timestampNanos > RECENT_BEACON_WINDOW_NS }
            recentBeacons.add(result)
            val strongest = recentBeacons.maxByOrNull { it.rssi } ?: return
            if (strongest.rssi < MIN_RSSI) return

            val strongestData = strongest.scanRecord
                ?.getManufacturerSpecificData(APPLE_COMPANY_ID)
                ?.let(::normalizePayload)
                ?: return
            val battery = decode(strongestData) ?: return

            Log.d(
                TAG,
                "accepted candidate address=${strongest.device.address} rssi=${strongest.rssi} " +
                    "payload=${strongestData.toHex()} levels=${battery.left}/${battery.right}/${battery.case}",
            )

            // Require two consistent broadcasts before publishing. This prevents
            // nearby beacons and packet noise from making the widget flicker.
            if (pendingBattery != null && sameLevels(pendingBattery!!, battery)) {
                pendingHits += 1
            } else {
                pendingBattery = battery
                pendingHits = 1
            }
            if (pendingHits < REQUIRED_CONSISTENT_SAMPLES) return

            val accepted = battery.copy(lastSeenMillis = System.currentTimeMillis())
            if (!sameLevels(store.read(), accepted)) {
                store.write(accepted)
                SampleWidgetProvider.refreshAll(this@AirPodsScannerService)
            }
        }
    }

    private fun decode(bytes: ByteArray): AirPodsBattery? {
        // Manufacturer data returned by ScanRecord excludes the 0x004C company id.
        // Apple proximity-pairing packets are 27 bytes and use byte offsets, not
        // character offsets in a hex string.
        if (bytes.size != AIRPODS_PAYLOAD_SIZE) return null
        if (u8(bytes[0]) != 0x07 || u8(bytes[1]) != 0x19) return null

        val status = u8(bytes[5])
        val modelId = (u8(bytes[3]) shl 8) or u8(bytes[4])
        if (modelId !in AIRPODS_MODEL_IDS) return null
        val budByte = u8(bytes[6])
        val caseByte = u8(bytes[7])
        val primaryIsLeft = status and FLAG_PRIMARY_IS_LEFT != 0
        val primaryNibble = budByte ushr 4
        val secondaryNibble = budByte and 0x0F
        val leftNibble = if (primaryIsLeft) primaryNibble else secondaryNibble
        val rightNibble = if (primaryIsLeft) secondaryNibble else primaryNibble
        val caseNibble = caseByte and 0x0F
        return AirPodsBattery(
            left = percent(leftNibble),
            right = percent(rightNibble),
            case = percent(caseNibble),
            budsInUse = status and (FLAG_PRIMARY_IN_EAR or FLAG_SECONDARY_IN_EAR) != 0,
            lastSeenMillis = System.currentTimeMillis(),
        )
    }

    /** HyperOS sometimes concatenates the same manufacturer AD structure twice. */
    private fun normalizePayload(bytes: ByteArray): ByteArray? = when {
        bytes.size == AIRPODS_PAYLOAD_SIZE -> bytes
        bytes.size == AIRPODS_PAYLOAD_SIZE * 2 &&
            bytes.copyOfRange(0, AIRPODS_PAYLOAD_SIZE)
                .contentEquals(bytes.copyOfRange(AIRPODS_PAYLOAD_SIZE, bytes.size)) ->
            bytes.copyOfRange(0, AIRPODS_PAYLOAD_SIZE)
        else -> null
    }

    private fun percent(value: Int): Int? = when {
        value in 0..10 -> value * 10
        value == NIBBLE_UNKNOWN -> null
        else -> null
    }

    private fun u8(value: Byte): Int = value.toInt() and 0xFF

    private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(u8(it)) }

    private fun sameLevels(first: AirPodsBattery, second: AirPodsBattery): Boolean =
        first.left == second.left && first.right == second.right &&
            first.case == second.case && first.budsInUse == second.budsInUse

    private fun notification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
        .setContentTitle(getString(R.string.airpods_scanner_title))
        .setContentText(getString(R.string.airpods_scanner_text))
        .setOngoing(true)
        .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.airpods_scanner_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    companion object {
        private const val APPLE_COMPANY_ID = 0x004C
        private const val AIRPODS_PAYLOAD_SIZE = 27
        private const val FLAG_PRIMARY_IS_LEFT = 0x20
        private const val FLAG_PRIMARY_IN_EAR = 0x02
        private const val FLAG_SECONDARY_IN_EAR = 0x08
        private const val NIBBLE_UNKNOWN = 0x0F
        // Apple proximity packets use the same company id for Find My and Beats.
        // Keep only known AirPods family model ids so a nearby tracker cannot win
        // the RSSI selection and overwrite the widget.
        private val AIRPODS_MODEL_IDS = setOf(
            0x0220, 0x0F20, 0x1320, 0x1920, 0x1B20,
            0x0E20, 0x1420, 0x2420, 0x2720,
        )
        private const val MIN_RSSI = -65
        private const val REQUIRED_CONSISTENT_SAMPLES = 2
        private const val RECENT_BEACON_WINDOW_NS = 10_000_000_000L
        private const val CHANNEL_ID = "airpods_scanner"
        private const val NOTIFICATION_ID = 18
        private const val TAG = "AirPodsScanner"
        private const val ACTION_REFRESH = "com.qiki.widgets.action.REFRESH_AIRPODS"

        fun start(context: Context) {
            val intent = Intent(context, AirPodsScannerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun refresh(context: Context) {
            val intent = Intent(context, AirPodsScannerService::class.java).setAction(ACTION_REFRESH)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
