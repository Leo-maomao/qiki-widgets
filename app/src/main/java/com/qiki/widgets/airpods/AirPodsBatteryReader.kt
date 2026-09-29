package com.qiki.widgets.airpods

import android.bluetooth.BluetoothManager
import android.content.Context
import android.net.Uri
import com.qiki.widgets.widget.SampleWidgetProvider

object AirPodsBatteryReader {
    private val provider = Uri.parse("content://com.android.bluetooth.ble.app.headsetdata.provider")

    fun read(context: Context): Boolean {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return false
        val device = adapter.bondedDevices.firstOrNull {
            runCatching { (it.name ?: it.alias ?: "").contains("airpods", ignoreCase = true) }
                .getOrDefault(false)
        } ?: return false
        val result = runCatching {
            context.contentResolver.call(provider, "getAirpodsState", device.address, null)
        }.getOrNull() ?: return false
        val left = result.getInt("leftBattery", -1).takeIf { it in 0..100 }
        val right = result.getInt("rightBattery", -1).takeIf { it in 0..100 }
        val box = result.getInt("boxBattery", -1).takeIf { it in 0..100 }
        if (left == null && right == null && box == null) return false
        val store = AirPodsBatteryStore(context)
        val current = store.read()
        store.write(current.copy(
            left = left ?: current.left,
            right = right ?: current.right,
            case = box ?: current.case,
            budsInUse = result.getInt("wearType", 0) == 2,
            lastSeenMillis = System.currentTimeMillis(),
        ))
        SampleWidgetProvider.refreshAll(context)
        return true
    }
}
