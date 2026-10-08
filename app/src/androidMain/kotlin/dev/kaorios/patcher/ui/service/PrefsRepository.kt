package dev.kaorios.patcher.ui.service

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateMapOf

/**
 * Single entry point for persisted UI state.
 *
 * Mirrors [androidx.compose.runtime.snapshot.SnapshotStateMap] over SharedPreferences so a
 * `remember*Preference` composable re-runs on write without a flow per key. Reads never seed
 * the map, which keeps composition free of state mutations; defaults live in
 * [registerDefaults] and at each call site.
 */
class PrefsRepository private constructor(
    private val prefs: SharedPreferences,
) {
    private val values = mutableStateMapOf<String, Any>()

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null) {
            values.clear()
            prefs.all.forEach { (k, v) -> if (v != null) values[k] = v }
        } else {
            val stored = prefs.all[key]
            if (stored == null) values.remove(key) else values[key] = stored
        }
    }

    init {
        prefs.all.forEach { (key, value) -> if (value != null) values[key] = value }
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    /** Seeds keys that have never been written so reads stay pure snapshot reads. */
    fun registerDefaults(defaults: Map<String, Any>) {
        for ((key, value) in defaults) {
            if (values.containsKey(key)) continue
            values[key] = value
            prefs.edit().putDefault(key, value).apply()
        }
    }

    fun getString(key: String, default: String): String = values[key] as? String ?: default

    fun getBoolean(key: String, default: Boolean): Boolean = values[key] as? Boolean ?: default

    fun getLong(key: String, default: Long): Long = values[key] as? Long ?: default

    fun putString(key: String, value: String) = write(key, value, prefs.edit().putString(key, value))

    fun putBoolean(key: String, value: Boolean) =
        write(key, value, prefs.edit().putBoolean(key, value))

    fun putLong(key: String, value: Long) = write(key, value, prefs.edit().putLong(key, value))

    private fun write(key: String, value: Any, editor: SharedPreferences.Editor) {
        values[key] = value
        editor.apply()
    }

    private fun SharedPreferences.Editor.putDefault(key: String, value: Any): SharedPreferences.Editor =
        when (value) {
            is String -> putString(key, value)
            is Boolean -> putBoolean(key, value)
            is Long -> putLong(key, value)
            else -> this
        }

    companion object {
        private const val FILE_NAME = "KaoriosPatcherPrefs"

        fun create(context: Context, defaults: Map<String, Any> = emptyMap()): PrefsRepository =
            PrefsRepository(
                context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            ).also { it.registerDefaults(defaults) }
    }
}