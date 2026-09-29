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

    private fun startScan() {
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (!adapter.isEnabled) return
        scanner = adapter.bluetoothLeScanner ?: return
        scanner?.startScan(
            null,
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            scanCallback,
        )
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val manufacturerData = result.scanRecord?.getManufacturerSpecificData(APPLE_COMPANY_ID)
                ?: return
            if (manufacturerData.size != AIRPODS_PAYLOAD_SIZE) return
            val battery = decode(manufacturerData) ?: return
            store.write(battery)
            SampleWidgetProvider.refreshAll(this@AirPodsScannerService)
        }
    }

    private fun decode(bytes: ByteArray): AirPodsBattery? {
        if (bytes.size <= 15) return null
        val isFlipped = (bytes[10].toInt() and 0x02) == 0
        val leftNibble = nibble(bytes[if (isFlipped) 12 else 13])
        val rightNibble = nibble(bytes[if (isFlipped) 13 else 12])
        val caseNibble = nibble(bytes[15])
        return AirPodsBattery(
            left = percent(leftNibble),
            right = percent(rightNibble),
            case = percent(caseNibble),
            lastSeenMillis = System.currentTimeMillis(),
        )
    }

    private fun nibble(value: Byte): Int = value.toInt() and 0x0F

    private fun percent(value: Int): Int? = when {
        value in 0..9 -> value * 10 + 5
        value == 10 -> 100
        else -> null
    }

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
        private const val CHANNEL_ID = "airpods_scanner"
        private const val NOTIFICATION_ID = 18

        fun start(context: Context) {
            val intent = Intent(context, AirPodsScannerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
