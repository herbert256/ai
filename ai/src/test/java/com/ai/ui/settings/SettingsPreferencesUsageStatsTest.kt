package com.ai.ui.settings

import android.content.SharedPreferences
import com.ai.data.ApiFormat
import com.ai.data.AppService
import com.ai.viewmodel.AppHomeMode
import com.ai.viewmodel.GeneralSettings
import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SettingsPreferencesUsageStatsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun flushUsageStats_writes_debounced_updates_to_disk() {
        val prefs = SettingsPreferences(MemorySharedPreferences(), tmp.root)
        val service = AppService(
            id = "UNIT_USAGE",
            baseUrl = "https://usage.example.com/",
            adminUrl = "",
            defaultModel = "model",
            apiFormat = ApiFormat.OPENAI_COMPATIBLE
        )
        val file = File(tmp.root, "usage-stats.json")

        prefs.updateUsageStats(service, "model", inputTokens = 1, outputTokens = 2)
        val firstWrite = file.readText()
        prefs.updateUsageStats(service, "model", inputTokens = 3, outputTokens = 4)

        assertThat(file.readText()).isEqualTo(firstWrite)

        prefs.flushUsageStats()

        @Suppress("DEPRECATION")
        val row = JsonParser().parse(file.readText()).asJsonArray[0].asJsonObject
        assertThat(row["callCount"].asInt).isEqualTo(2)
        assertThat(row["inputTokens"].asLong).isEqualTo(4)
        assertThat(row["outputTokens"].asLong).isEqualTo(6)
    }

    @Test fun loadGeneralSettings_defaults_appHomeMode_to_homeScreen() {
        val prefs = SettingsPreferences(MemorySharedPreferences(), tmp.root)

        assertThat(prefs.loadGeneralSettings().appHomeMode).isEqualTo(AppHomeMode.HOME_SCREEN)
    }

    @Test fun saveGeneralSettings_roundTrips_appHomeMode_homeBar() {
        val sharedPreferences = MemorySharedPreferences()
        val prefs = SettingsPreferences(sharedPreferences, tmp.root)

        prefs.saveGeneralSettings(GeneralSettings(appHomeMode = AppHomeMode.HOME_BAR))

        assertThat(SettingsPreferences(sharedPreferences, tmp.root).loadGeneralSettings().appHomeMode)
            .isEqualTo(AppHomeMode.HOME_BAR)
    }

    private class MemorySharedPreferences : SharedPreferences {
        private val values = LinkedHashMap<String, Any?>()

        override fun getAll(): MutableMap<String, *> = LinkedHashMap(values)
        override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (values[key] as? Set<String>)?.toMutableSet() ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor(values)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    }

    private class Editor(private val target: MutableMap<String, Any?>) : SharedPreferences.Editor {
        private val pending = LinkedHashMap<String, Any?>()
        private val removals = LinkedHashSet<String>()
        private var clear = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor = put(key, value)
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = put(key, values?.toSet())
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = put(key, value)
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = put(key, value)
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = put(key, value)
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = put(key, value)
        override fun remove(key: String?): SharedPreferences.Editor = apply {
            key?.let {
                removals += it
                pending.remove(it)
            }
        }
        override fun clear(): SharedPreferences.Editor = apply { clear = true }
        override fun commit(): Boolean = true
            .also { apply() }
        override fun apply() {
            if (clear) target.clear()
            removals.forEach { target.remove(it) }
            pending.forEach { (key, value) ->
                if (value == null) target.remove(key) else target[key] = value
            }
        }

        private fun put(key: String?, value: Any?): SharedPreferences.Editor = apply {
            key?.let {
                pending[it] = value
                removals.remove(it)
            }
        }
    }
}
