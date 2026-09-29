package com.qiki.widgets.airpods

import android.content.Context

data class AirPodsBattery(
    val left: Int? = null,
    val right: Int? = null,
    val case: Int? = null,
    val lastSeenMillis: Long = 0L,
) {
    val isAvailable: Boolean
        get() = left != null || right != null || case != null
}

class AirPodsBatteryStore(context: Context) {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun read(): AirPodsBattery = AirPodsBattery(
        left = preferences.getNullableInt(KEY_LEFT),
        right = preferences.getNullableInt(KEY_RIGHT),
        case = preferences.getNullableInt(KEY_CASE),
        lastSeenMillis = preferences.getLong(KEY_LAST_SEEN, 0L),
    )

    fun write(value: AirPodsBattery) {
        preferences.edit()
            .putNullableInt(KEY_LEFT, value.left)
            .putNullableInt(KEY_RIGHT, value.right)
            .putNullableInt(KEY_CASE, value.case)
            .putLong(KEY_LAST_SEEN, value.lastSeenMillis)
            .apply()
    }

    private fun android.content.SharedPreferences.getNullableInt(key: String): Int? =
        if (contains(key)) getInt(key, 0) else null

    private fun android.content.SharedPreferences.Editor.putNullableInt(key: String, value: Int?): android.content.SharedPreferences.Editor =
        if (value == null) remove(key) else putInt(key, value)

    private companion object {
        const val FILE_NAME = "airpods_battery"
        const val KEY_LEFT = "left"
        const val KEY_RIGHT = "right"
        const val KEY_CASE = "case"
        const val KEY_LAST_SEEN = "last_seen"
    }
}
