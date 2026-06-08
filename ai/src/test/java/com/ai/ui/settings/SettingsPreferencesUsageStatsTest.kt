package com.ai.ui.settings

import com.ai.data.preferences.SettingsPreferences

import com.ai.data.ApiFormat
import com.ai.data.AppService
import com.ai.testutil.MemorySharedPreferences
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

    @Test fun loadGeneralSettings_defaults_appHomeMode_to_homeBar() {
        val prefs = SettingsPreferences(MemorySharedPreferences(), tmp.root)

        assertThat(prefs.loadGeneralSettings().appHomeMode).isEqualTo(AppHomeMode.HOME_BAR)
    }

    @Test fun saveGeneralSettings_roundTrips_appHomeMode_homeBar() {
        val sharedPreferences = MemorySharedPreferences()
        val prefs = SettingsPreferences(sharedPreferences, tmp.root)

        prefs.saveGeneralSettings(GeneralSettings(appHomeMode = AppHomeMode.HOME_BAR))

        assertThat(SettingsPreferences(sharedPreferences, tmp.root).loadGeneralSettings().appHomeMode)
            .isEqualTo(AppHomeMode.HOME_BAR)
    }
}
