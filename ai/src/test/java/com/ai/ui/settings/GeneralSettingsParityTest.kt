package com.ai.ui.settings

import com.ai.data.preferences.SettingsPreferences

import com.ai.data.LogLevel
import com.ai.data.MetadataIcons
import com.ai.testutil.MemorySharedPreferences
import com.ai.viewmodel.AppHomeMode
import com.ai.viewmodel.GeneralSettings
import com.ai.viewmodel.ModelNameLayout
import com.ai.viewmodel.ReportTitleMode
import com.ai.viewmodel.UiColorMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Load/save parity for [GeneralSettings] (audit recommendation D02 / T01).
 *
 * `loadGeneralSettings` and `saveGeneralSettings` are hand-written field
 * mirrors — cheap to write once, expensive to trust forever. This test fails
 * if a future field is added to [GeneralSettings] but not wired through both
 * the save and load paths.
 *
 * Three fields are intentionally excluded from the whole-object round-trip and
 * asserted separately, because they are transform-coupled rather than plain
 * mirrors: [GeneralSettings.uiColorOverrides] is forced to the full normalised
 * palette on save, and [GeneralSettings.uiCardBackgroundArgb] /
 * [GeneralSettings.uiButtonBackgroundArgb] are derived from that map (their
 * own prefs keys are written but never read back). See
 * [uiBackgroundArgb_roundTrips_only_via_colorOverridesMap].
 */
class GeneralSettingsParityTest {
    @get:Rule val tmp = TemporaryFolder()

    /** A [GeneralSettings] with every field set to a distinctive, non-default,
     *  transform-stable value so a missing save/load wiring is observable. */
    private fun fullyNonDefault() = GeneralSettings(
        userName = "Distinctive-User",
        huggingFaceApiKey = "hf-key-123",
        openRouterApiKey = "or-key-456",
        artificialAnalysisApiKey = "aa-key-789",
        defaultEmail = "tester@example.com",
        defaultTypePaths = mapOf("TEXT" to "/v1/text", "VISION" to "/v1/vision"),
        loggingMasterEnabled = false,
        tracingEnabled = false,
        showLadybugIcons = false,
        auditLogEnabled = true,
        usageStatsEnabled = false,
        fullScreen = false,
        modelNameLayout = ModelNameLayout.PROVIDER_AND_MODEL,
        appHomeMode = AppHomeMode.HOME_SCREEN,
        uiCardBackgroundArgb = 0x11223344,
        uiButtonBackgroundArgb = 0x55667788,
        rankingWeights = mapOf("rerank" to 7, "judges" to 3, "translations" to 9),
        uiColorOverrides = mapOf("CardBackgroundAlt" to 0x11223344),
        uiColorOverridesDay = mapOf("CardBackgroundAlt" to 0x21436587),
        uiColorMode = UiColorMode.AUTO,
        metadataEnabled = false,
        iconGenEnabled = false,
        reportLanguageGenEnabled = false,
        reportTitleMode = ReportTitleMode.Manual,
        perModelIconGenEnabled = false,
        perModelTitleGenEnabled = false,
        useInternalPromptsIcons = false,
        autostartItemsEnabled = true,
        autostartFanMeta = false,
        autoCreateRerankAndModeration = false,
        metadataIcons = MetadataIcons().copy(reportIcon = "🦊"), // 🦊
        appWideSystemPromptId = "sysprompt-app",
        appWideParametersIds = listOf("p1", "p2"),
        reportModelSystemPromptId = "sysprompt-model",
        reportModelParametersIds = listOf("p3"),
        recentReportModels = listOf("openai|gpt-4o", "anthropic|claude"),
        streamingReadTimeoutSec = 123,
        nonStreamingReadTimeoutSec = 456,
        maxCallsPerProviderPerMinute = 42,
        maxConcurrentCallsPerProvider = 7,
        maxConcurrentApiCalls = 99,
        maxRetriesOn429 = 9,
        retryBackoffMs429 = 2_222L,
        maxRetriesOn529 = 8,
        retryBackoffMs529 = 3_333L,
        typeABenchEnabled = false,
        typeABenchSeconds = 17,
        typeABenchMaxAttempts = 11,
        logLevel = LogLevel.DEBUG,
        showKnowledgeCard = true,
        experimentalFeaturesEnabled = true,
        pinnedDashboardCards = setOf("alpha", "beta", "gamma"),
        dashboardCardOrder = listOf("gamma", "beta", "alpha"),
    )

    /** Drop the transform-coupled colour fields so the rest of the object can be
     *  compared as a strict round-trip. */
    private fun GeneralSettings.withoutColorCoupledFields() = copy(
        uiColorOverrides = emptyMap(),
        uiCardBackgroundArgb = 0,
        uiButtonBackgroundArgb = 0,
    )

    @Test fun everyField_roundTripsThroughSaveAndLoad() {
        val backing = MemorySharedPreferences()
        val saved = fullyNonDefault()

        SettingsPreferences(backing, tmp.root).saveGeneralSettings(saved)
        val loaded = SettingsPreferences(backing, tmp.root).loadGeneralSettings()

        // Whole-object equality (minus the colour-coupled fields) means any new
        // GeneralSettings field that is not persisted will fail this assertion.
        assertThat(loaded.withoutColorCoupledFields())
            .isEqualTo(saved.withoutColorCoupledFields())
    }

    @Test fun uiBackgroundArgb_roundTrips_only_via_colorOverridesMap() {
        val backing = MemorySharedPreferences()
        // Set the override-map entries; the standalone *Argb fields are derived.
        val saved = GeneralSettings(
            uiColorOverrides = mapOf(
                "CardBackgroundAlt" to 0x0A0B0C0D,
                "ButtonBackground" to 0x1A1B1C1D,
            ),
        )

        SettingsPreferences(backing, tmp.root).saveGeneralSettings(saved)
        val loaded = SettingsPreferences(backing, tmp.root).loadGeneralSettings()

        assertThat(loaded.uiCardBackgroundArgb).isEqualTo(0x0A0B0C0D)
        assertThat(loaded.uiButtonBackgroundArgb).isEqualTo(0x1A1B1C1D)
    }

    @Test fun standaloneArgbField_isIgnored_whenOverrideMapAbsent() {
        // Documents the current behaviour: the dedicated card/button ARGB prefs
        // keys are written on save but never read on load — the field only
        // persists through uiColorOverrides. A bare *Argb field load-defaults.
        val backing = MemorySharedPreferences()
        val saved = GeneralSettings(uiCardBackgroundArgb = 0x7F7F7F7F)

        SettingsPreferences(backing, tmp.root).saveGeneralSettings(saved)
        val loaded = SettingsPreferences(backing, tmp.root).loadGeneralSettings()

        assertThat(loaded.uiCardBackgroundArgb).isEqualTo(GeneralSettings().uiCardBackgroundArgb)
    }

    @Test fun absentKeys_seedDocumentedDefaults() {
        val loaded = SettingsPreferences(MemorySharedPreferences(), tmp.root).loadGeneralSettings()

        assertThat(loaded.appHomeMode).isEqualTo(AppHomeMode.HOME_BAR)
        assertThat(loaded.reportTitleMode).isEqualTo(ReportTitleMode.AI)
        assertThat(loaded.modelNameLayout).isEqualTo(ModelNameLayout.MODEL_ONLY)
        assertThat(loaded.uiColorMode).isEqualTo(UiColorMode.NIGHT)
        assertThat(loaded.logLevel).isEqualTo(LogLevel.WARN)
        assertThat(loaded.loggingMasterEnabled).isTrue()
        assertThat(loaded.showLadybugIcons).isTrue()
        assertThat(loaded.pinnedDashboardCards).isEqualTo(GeneralSettings().pinnedDashboardCards)
        // NOTE: the load-path network-cap defaults (30/3/50) intentionally diverge
        // from the GeneralSettings data-class defaults (60/5/100); a fresh load
        // yields the load-path values. Asserted so the divergence is explicit.
        assertThat(loaded.maxCallsPerProviderPerMinute).isEqualTo(30)
        assertThat(loaded.maxConcurrentCallsPerProvider).isEqualTo(3)
        assertThat(loaded.maxConcurrentApiCalls).isEqualTo(50)
    }

    @Test fun emptyCollections_stayEmpty() {
        val backing = MemorySharedPreferences()
        SettingsPreferences(backing, tmp.root).saveGeneralSettings(GeneralSettings())
        val loaded = SettingsPreferences(backing, tmp.root).loadGeneralSettings()

        assertThat(loaded.appWideParametersIds).isEmpty()
        assertThat(loaded.reportModelParametersIds).isEmpty()
        assertThat(loaded.dashboardCardOrder).isEmpty()
        assertThat(loaded.rankingWeights).isEmpty()
        assertThat(loaded.defaultTypePaths).isEmpty()
        assertThat(loaded.recentReportModels).isEmpty()
    }

    @Test fun unknownEnumValues_fallBackToDefaults() {
        // Mirror of the private KEY_* literals in SettingsPreferences. If those
        // constants change, this test must follow — that coupling is the point:
        // a corrupted/legacy enum string must not crash the load.
        val backing = MemorySharedPreferences()
        backing.edit()
            .putString("log_level", "NOPE")
            .putString("app_home", "NOPE")
            .putString("ui_color_mode", "NOPE")
            .putString("report_title_mode", "NOPE")
            .putString("model_name_layout", "NOPE")
            .apply()

        val loaded = SettingsPreferences(backing, tmp.root).loadGeneralSettings()

        assertThat(loaded.logLevel).isEqualTo(LogLevel.WARN)
        assertThat(loaded.appHomeMode).isEqualTo(AppHomeMode.HOME_BAR)
        assertThat(loaded.uiColorMode).isEqualTo(UiColorMode.NIGHT)
        assertThat(loaded.reportTitleMode).isEqualTo(ReportTitleMode.AI)
        assertThat(loaded.modelNameLayout).isEqualTo(ModelNameLayout.MODEL_ONLY)
    }
}
