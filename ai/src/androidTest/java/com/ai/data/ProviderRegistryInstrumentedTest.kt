package com.ai.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProviderRegistryInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun reset() { ProviderRegistry.resetToDefaults(context) }

    @Test fun init_loads_providers_from_setup_json_assets() {
        val all = ProviderRegistry.getAll()
        assertThat(all).isNotEmpty()
        // Every provider has a non-blank id and base URL.
        all.forEach { svc ->
            assertThat(svc.id).isNotEmpty()
            assertThat(svc.baseUrl).isNotEmpty()
            assertThat(svc.displayName).isNotEmpty()
        }
    }

    @Test fun findById_returns_a_known_provider() {
        val all = ProviderRegistry.getAll()
        val first = all.firstOrNull() ?: error("provider registry should not be empty")
        val byId = ProviderRegistry.findById(first.id)
        assertThat(byId).isNotNull()
        assertThat(byId!!.id).isEqualTo(first.id)
    }

    @Test fun findById_returns_null_for_unknown_id() {
        assertThat(ProviderRegistry.findById("DEFINITELY-NOT-A-REAL-PROVIDER")).isNull()
    }

    @Test fun add_then_remove_round_trips_a_synthetic_provider() {
        val testProvider = AppService(
            id = "UNIT_REGISTRY_PROBE",
            displayName = "Unit Registry Probe",
            baseUrl = "https://probe.example.com/",
            adminUrl = "",
            defaultModel = "model"
        )
        ProviderRegistry.add(testProvider)
        try {
            assertThat(ProviderRegistry.findById("UNIT_REGISTRY_PROBE")).isNotNull()
        } finally {
            ProviderRegistry.remove("UNIT_REGISTRY_PROBE")
        }
        assertThat(ProviderRegistry.findById("UNIT_REGISTRY_PROBE")).isNull()
    }

    @Test fun update_replaces_existing_entry() {
        val all = ProviderRegistry.getAll()
        val first = all.firstOrNull() ?: error("registry empty")
        val updated = AppService(
            id = first.id,
            displayName = "Renamed Display",
            baseUrl = first.baseUrl,
            adminUrl = first.adminUrl,
            defaultModel = first.defaultModel
        )
        ProviderRegistry.update(updated)
        try {
            assertThat(ProviderRegistry.findById(first.id)?.displayName).isEqualTo("Renamed Display")
        } finally {
            ProviderRegistry.update(first)
        }
    }
}
