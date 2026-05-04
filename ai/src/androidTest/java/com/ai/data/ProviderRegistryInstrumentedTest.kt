package com.ai.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ai.util.PersistentStateGuard
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.ClassRule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProviderRegistryInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun reset() { ProviderRegistry.resetToDefaults(context) }

    companion object {
        @ClassRule @JvmField val stateGuard = PersistentStateGuard()
    }

    @Test fun fresh_init_is_empty() {
        // Bundled providers are no longer auto-seeded; resetToDefaults
        // clears prefs and re-inits an empty registry.
        assertThat(ProviderRegistry.getAll()).isEmpty()
    }

    @Test fun import_from_asset_seeds_providers_then_is_idempotent() {
        // First import populates from the bundled assets/providers.json.
        val firstAdded = ProviderRegistry.importFromAsset(context)
        assertThat(firstAdded).isGreaterThan(0)
        val all = ProviderRegistry.getAll()
        assertThat(all).hasSize(firstAdded)
        all.forEach { svc ->
            assertThat(svc.id).isNotEmpty()
            assertThat(svc.baseUrl).isNotEmpty()
            assertThat(svc.displayName).isNotEmpty()
        }
        // Second call is a no-op — every id already present.
        val secondAdded = ProviderRegistry.importFromAsset(context)
        assertThat(secondAdded).isEqualTo(0)
    }

    @Test fun findById_returns_null_on_empty_registry() {
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
        // Seed via the on-demand import so there's something to update.
        ProviderRegistry.importFromAsset(context)
        val all = ProviderRegistry.getAll()
        val first = all.firstOrNull() ?: error("import yielded no providers")
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
