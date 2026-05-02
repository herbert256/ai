package com.ai.ui.settings

import android.content.SharedPreferences
import com.ai.data.ApiFormat
import com.ai.data.AppService
import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SettingsPreferencesUsageStatsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun flushUsageStats_writes_debounced_updates_to_disk() {
        val prefs = SettingsPreferences(UnusedSharedPreferences(), tmp.root)
        val service = AppService(
            id = "UNIT_USAGE",
            displayName = "Unit Usage",
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

    private class UnusedSharedPreferences : SharedPreferences {
        override fun getAll(): MutableMap<String, *> = mutableMapOf<String, Any?>()
        override fun getString(key: String?, defValue: String?): String? = defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = defValue
        override fun getLong(key: String?, defValue: Long): Long = defValue
        override fun getFloat(key: String?, defValue: Float): Float = defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue
        override fun contains(key: String?): Boolean = false
        override fun edit(): SharedPreferences.Editor = NoopEditor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    }

    private class NoopEditor : SharedPreferences.Editor {
        override fun putString(key: String?, value: String?): SharedPreferences.Editor = this
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = this
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = this
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = this
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = this
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = this
        override fun remove(key: String?): SharedPreferences.Editor = this
        override fun clear(): SharedPreferences.Editor = this
        override fun commit(): Boolean = true
        override fun apply() = Unit
    }
}
