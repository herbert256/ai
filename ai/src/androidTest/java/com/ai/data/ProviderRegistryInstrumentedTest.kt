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
            assertThat(svc.id).isNotEmpty()
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
            id = "UnitRegistryProbe",
            baseUrl = "https://probe.example.com/",
            adminUrl = "",
            defaultModel = "model"
        )
        ProviderRegistry.add(testProvider)
        try {
            assertThat(ProviderRegistry.findById("UnitRegistryProbe")).isNotNull()
        } finally {
            ProviderRegistry.remove("UnitRegistryProbe")
        }
        assertThat(ProviderRegistry.findById("UnitRegistryProbe")).isNull()
    }

    @Test fun update_replaces_existing_entry() {
        // Seed via the on-demand import so there's something to update.
        ProviderRegistry.importFromAsset(context)
        val all = ProviderRegistry.getAll()
        val first = all.firstOrNull() ?: error("import yielded no providers")
        // Update with new adminUrl (id is identity — can't change it
        // without orphaning every persisted reference).
        val updated = AppService(
            id = first.id,
            baseUrl = first.baseUrl,
            adminUrl = "https://example.test/admin/changed",
            defaultModel = first.defaultModel
        )
        ProviderRegistry.update(updated)
        try {
            assertThat(ProviderRegistry.findById(first.id)?.adminUrl)
                .isEqualTo("https://example.test/admin/changed")
        } finally {
            ProviderRegistry.update(first)
        }
    }
}
