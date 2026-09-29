package com.qiki.widgets.airpods

import android.app.Service
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
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
    private var a2dp: BluetoothA2dp? = null
    private var headset: BluetoothHeadset? = null
    @Volatile private var pairedAirPodsConnected = false
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        registerConnectionTracking()
        startScan()
        mainHandler.postDelayed({ stopSelf() }, SCAN_WINDOW_MILLIS)
    }

    override fun onDestroy() {
        scanner?.stopScan(scanCallback)
        scanner = null
        runCatching { unregisterReceiver(connectionReceiver) }
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        a2dp?.let { adapter.closeProfileProxy(BluetoothProfile.A2DP, it) }
        headset?.let { adapter.closeProfileProxy(BluetoothProfile.HEADSET, it) }
        mainHandler.removeCallbacksAndMessages(null)
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
            mainHandler.removeCallbacksAndMessages(null)
            mainHandler.postDelayed({ stopSelf() }, SCAN_WINDOW_MILLIS)
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

    private fun registerConnectionTracking() {
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        ContextCompat.registerReceiver(
            this,
            connectionReceiver,
            IntentFilter().apply {
                addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
                addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
                addAction(ACTION_BLUETOOTH_BATTERY_CHANGED)
            },
            // Bluetooth broadcasts are emitted by the system Bluetooth process.
            ContextCompat.RECEIVER_EXPORTED,
        )
        adapter.getProfileProxy(this, profileListener, BluetoothProfile.A2DP)
        adapter.getProfileProxy(this, profileListener, BluetoothProfile.HEADSET)
        refreshConnectionState()
    }

    private fun refreshConnectionState() {
        val bondedDevices = bondedAirPodsDevices()
        val bonded = bondedDevices.map { it.address }.toSet()
        pairedAirPodsConnected = listOfNotNull(
            a2dp?.connectedDevices,
            headset?.connectedDevices,
        ).flatten().any { it.address in bonded }
        Log.d(TAG, "paired AirPods audio connected=$pairedAirPodsConnected")
        bondedDevices.forEach(::readMetadataBattery)
    }

    private fun bondedAirPodsAddresses(): Set<String> {
        return bondedAirPodsDevices().map { it.address }.toSet()
    }

    private fun bondedAirPodsDevices(): List<BluetoothDevice> {
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        return adapter.bondedDevices
            .filter { device ->
                val name = runCatching { device.name ?: device.alias ?: "" }.getOrDefault("")
                name.contains("airpods", ignoreCase = true) ||
                    device.uuids?.any {
                        it.uuid.toString().equals(AAP_SERVICE_UUID, ignoreCase = true)
                    } == true
            }
    }

    private fun readMetadataBattery(device: BluetoothDevice) {
        val getMetadata = runCatching {
            device.javaClass.getMethod("getMetadata", Int::class.javaPrimitiveType)
        }.getOrNull() ?: return
        fun level(key: Int): Int? = runCatching {
            val value = getMetadata.invoke(device, key) as? ByteArray ?: return@runCatching null
            val text = value.toString(Charsets.UTF_8).trim()
            val parsed = text.toIntOrNull() ?: value.singleOrNull()?.toInt()?.and(0xFF)
            parsed?.takeIf { it in 0..100 }
        }.getOrNull()
        val current = store.read()
        val left = level(METADATA_LEFT_BATTERY)
        val right = level(METADATA_RIGHT_BATTERY)
        val case = level(METADATA_CASE_BATTERY)
        val next = current.copy(
            left = left ?: current.left,
            right = right ?: current.right,
            case = case ?: current.case,
            lastSeenMillis = if (left != null || right != null || case != null) {
                System.currentTimeMillis()
            } else current.lastSeenMillis,
        )
        if (next != current) {
            Log.d(TAG, "system metadata battery address=${device.address} ${next.left}/${next.right}/${next.case}")
            store.write(next)
            SampleWidgetProvider.refreshAll(this)
        }
        readPublicBatteryLevel(device)?.let { level ->
            Log.d(TAG, "system device battery address=${device.address} level=$level")
            val latest = store.read().copy(case = level, lastSeenMillis = System.currentTimeMillis())
            store.write(latest)
            SampleWidgetProvider.refreshAll(this)
        }
    }

    private fun readPublicBatteryLevel(device: BluetoothDevice): Int? = runCatching {
        val method = device.javaClass.getMethod("getBatteryLevel")
        (method.invoke(device) as? Int)?.takeIf { it in 0..100 }
    }.getOrNull()

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            when (profile) {
                BluetoothProfile.A2DP -> a2dp = proxy as BluetoothA2dp
                BluetoothProfile.HEADSET -> headset = proxy as BluetoothHeadset
            }
            refreshConnectionState()
        }

        override fun onServiceDisconnected(profile: Int) {
            when (profile) {
                BluetoothProfile.A2DP -> a2dp = null
                BluetoothProfile.HEADSET -> headset = null
            }
            refreshConnectionState()
        }
    }

    private val connectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_BLUETOOTH_BATTERY_CHANGED) {
                handleSystemBattery(intent)
            } else {
                refreshConnectionState()
            }
        }
    }

    private fun handleSystemBattery(intent: Intent) {
        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            ?: return
        if (device.address !in bondedAirPodsAddresses()) return
        val level = intent.getIntExtra(EXTRA_BLUETOOTH_BATTERY_LEVEL, -1)
        if (level !in 0..100) return
        Log.d(TAG, "system Bluetooth battery address=${device.address} level=$level")
        val current = store.read()
        store.write(
            current.copy(
                case = level,
                lastSeenMillis = System.currentTimeMillis(),
            ),
        )
        SampleWidgetProvider.refreshAll(this@AirPodsScannerService)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed error=$errorCode")
        }

        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!pairedAirPodsConnected) return
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
            // AirPods BLE packets are coarse 10% levels and use random addresses.
            // They are intentionally never published as a 1% reading.
            Log.d(TAG, "matched AirPods broadcast address=${strongest.device.address} rssi=${strongest.rssi}")
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

    companion object {
        private const val APPLE_COMPANY_ID = 0x004C
        private const val ACTION_BLUETOOTH_BATTERY_CHANGED =
            "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        private const val EXTRA_BLUETOOTH_BATTERY_LEVEL =
            "android.bluetooth.device.extra.BATTERY_LEVEL"
        private const val METADATA_LEFT_BATTERY = 10
        private const val METADATA_RIGHT_BATTERY = 11
        private const val METADATA_CASE_BATTERY = 12
        private const val AAP_SERVICE_UUID = "74ec2172-0bad-4d01-8f77-997b2be0722a"
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
        private const val RECENT_BEACON_WINDOW_NS = 10_000_000_000L
        private const val SCAN_WINDOW_MILLIS = 12_000L
        private const val TAG = "AirPodsScanner"
        private const val ACTION_REFRESH = "com.qiki.widgets.action.REFRESH_AIRPODS"

        fun start(context: Context) {
            val intent = Intent(context, AirPodsScannerService::class.java)
            context.startService(intent)
        }

        fun refresh(context: Context) {
            val intent = Intent(context, AirPodsScannerService::class.java).setAction(ACTION_REFRESH)
            context.startService(intent)
        }
    }
}
