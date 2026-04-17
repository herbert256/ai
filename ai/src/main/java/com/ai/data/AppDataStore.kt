package com.ai.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Preferences DataStore. Coroutine-native replacement for SharedPreferences with atomic
 * multi-key writes and Flow-based reads. Introduced as a migration seed — full migration
 * from SharedPreferences is incremental.
 *
 * First concern on DataStore: the one-shot setup_imported bootstrap flag.
 */
internal val Context.appDataStore: DataStore<Preferences> by preferencesDataStore(name = "app_prefs")

object AppPrefKeys {
    val SETUP_IMPORTED = booleanPreferencesKey("setup_imported")
}

suspend fun Context.readBoolean(key: Preferences.Key<Boolean>, default: Boolean = false): Boolean =
    appDataStore.data.map { it[key] ?: default }.first()

suspend fun Context.writeBoolean(key: Preferences.Key<Boolean>, value: Boolean) {
    appDataStore.edit { it[key] = value }
}
