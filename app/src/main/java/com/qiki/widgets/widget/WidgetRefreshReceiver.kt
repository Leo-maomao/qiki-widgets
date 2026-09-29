package com.qiki.widgets.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.qiki.widgets.airpods.AirPodsBatteryReader

class WidgetRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == SampleWidgetProvider.ACTION_REFRESH) {
            AirPodsBatteryReader.read(context)
            SampleWidgetProvider.refreshAll(context)
        }
    }
}
