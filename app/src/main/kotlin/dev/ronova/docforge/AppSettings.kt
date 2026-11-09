package dev.ronova.docforge

import android.content.Context
import android.content.res.Configuration

/** Настройки приложения (хранятся в SharedPreferences). */
class AppSettings(private val context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Тема документов: 0 — как в системе, 1 — светлая, 2 — тёмная. */
    var nightMode: Int
        get() = prefs.getInt("night", 0)
        set(value) = prefs.edit().putInt("night", value).apply()

    /** Размер текста Word/Excel/LibreOffice-документов, %. */
    var textZoom: Int
        get() = prefs.getInt("zoom", 100)
        set(value) = prefs.edit().putInt("zoom", value).apply()

    var keepScreenOn: Boolean
        get() = prefs.getBoolean("keep_on", false)
        set(value) = prefs.edit().putBoolean("keep_on", value).apply()

    fun isNight(): Boolean = when (nightMode) {
        1 -> false
        2 -> true
        else -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }
}
