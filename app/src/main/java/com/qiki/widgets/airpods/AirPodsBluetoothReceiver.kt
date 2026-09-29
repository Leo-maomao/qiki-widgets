package com.qiki.widgets.airpods

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothHeadset
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.qiki.widgets.widget.SampleWidgetProvider

class AirPodsBluetoothReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
            BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED,
            "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED" ->
                if (AirPodsBatteryReader.read(context)) {
                    SampleWidgetProvider.refreshAll(context)
                }
        }
    }
}
