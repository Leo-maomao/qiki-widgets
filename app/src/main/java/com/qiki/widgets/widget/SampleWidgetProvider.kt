package com.qiki.widgets.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.bluetooth.BluetoothManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.qiki.widgets.MainActivity
import com.qiki.widgets.R
import com.qiki.widgets.airpods.AirPodsBatteryStore
import com.qiki.widgets.airpods.AirPodsScannerService
import com.qiki.widgets.airpods.AirPodsBattery
import android.net.Uri
import java.text.DateFormat
import java.util.Date

/**
 * Minimal standard App Widget used to validate the host-app-to-desktop flow.
 * Product components should move their data and refresh policy into widget-core.
 */
class SampleWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        refreshAll(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            readXiaomiAirPodsState(context)
            AirPodsScannerService.refresh(context)
            refreshAll(context)
        }
    }

    private fun readXiaomiAirPodsState(context: Context) {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        val device = adapter.bondedDevices.firstOrNull {
            (runCatching { it.name ?: it.alias ?: "" }.getOrDefault(""))
                .contains("airpods", ignoreCase = true)
        } ?: return
        val result = runCatching {
            context.contentResolver.call(
                Uri.parse("content://com.android.bluetooth.ble.app.headsetdata.provider"),
                "getAirpodsState",
                device.address,
                null,
            )
        }.getOrNull() ?: return
        val left = result.getInt("leftBattery", -1).takeIf { it in 0..100 }
        val right = result.getInt("rightBattery", -1).takeIf { it in 0..100 }
        val box = result.getInt("boxBattery", -1).takeIf { it in 0..100 }
        if (left == null && right == null && box == null) return
        val current = AirPodsBatteryStore(context).read()
        AirPodsBatteryStore(context).write(
            current.copy(
                left = left ?: current.left,
                right = right ?: current.right,
                case = box ?: current.case,
                budsInUse = result.getInt("wearType", 0) == 2,
                lastSeenMillis = System.currentTimeMillis(),
            ),
        )
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        updateAllWidgets(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
    }

    private fun updateAllWidgets(context: Context) = refreshAll(context)

    companion object {
        const val ACTION_REFRESH = "com.qiki.widgets.action.REFRESH_WIDGET"

        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, SampleWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(component)
            updateWidget(context, manager, AirPodsBatteryStore(context).read(), *ids)
        }
    }

}

private fun updateWidget(
    context: Context,
    manager: AppWidgetManager,
    battery: com.qiki.widgets.airpods.AirPodsBattery,
    vararg appWidgetIds: Int,
) {
    val openAppPendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val refreshPendingIntent = PendingIntent.getBroadcast(
        context,
        1,
        Intent(context, SampleWidgetProvider::class.java).setAction(SampleWidgetProvider.ACTION_REFRESH),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    appWidgetIds.forEach { appWidgetId ->
        val views = RemoteViews(context.packageName, R.layout.widget_sample).apply {
            setTextViewText(R.id.widget_left, percentText(battery.left))
            setTextViewText(R.id.widget_right, percentText(battery.right))
            setTextViewText(R.id.widget_case, percentText(battery.case))
            setTextViewText(R.id.widget_updated_at, updatedText(battery))
            setOnClickPendingIntent(R.id.widget_refresh, refreshPendingIntent)
            setOnClickPendingIntent(R.id.widget_title, openAppPendingIntent)
            setOnClickPendingIntent(R.id.widget_left, openAppPendingIntent)
            setOnClickPendingIntent(R.id.widget_right, openAppPendingIntent)
            setOnClickPendingIntent(R.id.widget_case, openAppPendingIntent)
            setOnClickPendingIntent(R.id.widget_updated_at, openAppPendingIntent)
        }
        manager.updateAppWidget(appWidgetId, views)
    }
}

private fun percentText(value: Int?): String = value?.let { "$it%" } ?: "--"

private fun updatedText(
    battery: com.qiki.widgets.airpods.AirPodsBattery,
): String = if (battery.lastSeenMillis == 0L) {
    "等待已配对 AirPods 连接"
} else {
    val updatedAt = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(battery.lastSeenMillis))
    "数据时间 $updatedAt"
}
