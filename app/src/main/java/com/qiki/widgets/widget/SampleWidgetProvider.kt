package com.qiki.widgets.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.qiki.widgets.MainActivity
import com.qiki.widgets.R
import com.qiki.widgets.airpods.AirPodsBatteryStore
import com.qiki.widgets.airpods.AirPodsScannerService
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
            AirPodsScannerService.refresh(context)
            refreshAll(context)
        }
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
            setTextViewText(R.id.widget_status, statusText(battery))
            setTextViewText(R.id.widget_updated_at, updatedText(battery))
            setOnClickPendingIntent(R.id.widget_refresh, refreshPendingIntent)
            setOnClickPendingIntent(R.id.widget_title, openAppPendingIntent)
            setOnClickPendingIntent(R.id.widget_status, openAppPendingIntent)
            setOnClickPendingIntent(R.id.widget_updated_at, openAppPendingIntent)
        }
        manager.updateAppWidget(appWidgetId, views)
    }
}

private fun statusText(battery: com.qiki.widgets.airpods.AirPodsBattery): String =
    if (!battery.isAvailable) "未发现 AirPods，请保持耳机盒打开" else if (!battery.budsInUse) {
        "盒 ${battery.case?.let { "$it%" } ?: "--"}  · 佩戴耳机后显示左右耳"
    } else
        "左 ${battery.left?.let { "$it%" } ?: "--"}  右 ${battery.right?.let { "$it%" } ?: "--"}  盒 ${battery.case?.let { "$it%" } ?: "--"}"

private fun updatedText(
    battery: com.qiki.widgets.airpods.AirPodsBattery,
): String = if (battery.lastSeenMillis == 0L) {
    "等待 AirPods 广播"
} else {
    val updatedAt = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(battery.lastSeenMillis))
    "数据时间 $updatedAt"
}
