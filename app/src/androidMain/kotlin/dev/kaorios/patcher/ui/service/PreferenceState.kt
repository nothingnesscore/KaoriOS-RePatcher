package dev.kaorios.patcher.ui.service

import androidx.compose.runtime.Composable

/**
 * Composable reads over [PrefsRepository].
 *
 * The repository map is snapshot state, so every helper re-runs when the key changes and
 * composes to a constant when it does not.
 */
@Composable
fun rememberStringPreference(
    prefs: PrefsRepository,
    key: String,
    default: String,
): String = prefs.getString(key, default)

@Composable
fun rememberBooleanPreference(
    prefs: PrefsRepository,
    key: String,
    default: Boolean = false,
): Boolean = prefs.getBoolean(key, default)

@Composable
fun rememberLongPreference(
    prefs: PrefsRepository,
    key: String,
    default: Long = 0L,
): Long = prefs.getLong(key, default)