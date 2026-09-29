package com.qiki.widgets.airpods

import android.bluetooth.BluetoothManager
import android.content.Context
import android.net.Uri
import android.util.Log
import com.qiki.widgets.widget.SampleWidgetProvider

object AirPodsBatteryReader {
    private val provider = Uri.parse("content://com.android.bluetooth.ble.app.headsetdata.provider")

    fun read(context: Context): Boolean {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return false
        val device = adapter.bondedDevices.firstOrNull {
            runCatching {
                val name = it.name ?: it.alias ?: ""
                name.contains("airpods", ignoreCase = true) ||
                    it.uuids?.any { uuid -> uuid.uuid.toString().equals("74ec2172-0bad-4d01-8f77-997b2be0722a", true) } == true
            }.getOrDefault(false)
        } ?: return false
        val result = runCatching {
            context.contentResolver.call(provider, "getAirpodsState", device.address, null)
        }.onFailure { Log.w("AirPodsBatteryReader", "provider call failed", it) }.getOrNull() ?: return false
        Log.d("AirPodsBatteryReader", "provider result=${result.keySet()}")
        val left = result.getInt("leftBattery", -1).takeIf { it in 0..100 }
        val right = result.getInt("rightBattery", -1).takeIf { it in 0..100 }
        val box = result.getInt("boxBattery", -1).takeIf { it in 0..100 }
        if (left == null && right == null && box == null) return false
        val store = AirPodsBatteryStore(context)
        val current = store.read()
        store.write(current.copy(
            deviceName = runCatching { device.name ?: device.alias }.getOrNull() ?: current.deviceName,
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
