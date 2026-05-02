package com.ai.util

import android.content.Context
import com.ai.data.AppService
import com.ai.data.ProviderRegistry

/**
 * Test fixture: a synthetic [AppService] registered into
 * [ProviderRegistry] so the rest of the app's code paths
 * (`AppService.findById`, `AppService.entries`, the providerLabelFor
 * hostname-matching helpers in TraceScreen / ZippedHtmlExport) all
 * resolve to it. Use [register] in @Before and [unregister] in
 * @After. Hostname is `test.example` to avoid collisions with real
 * providers loaded from setup.json.
 */
object TestProvider {
    const val ID = "UNIT_TEST_PROV"
    const val DISPLAY = "UnitProv"
    const val HOST = "test.example"
    const val BASE_URL = "https://$HOST/"
    const val MODEL = "test-model"

    val service = AppService(
        id = ID,
        displayName = DISPLAY,
        baseUrl = BASE_URL,
        adminUrl = "",
        defaultModel = MODEL
    )

    fun register(context: Context) {
        ProviderRegistry.init(context)
        if (ProviderRegistry.findById(ID) == null) {
            ProviderRegistry.add(service)
        }
    }

    fun unregister() {
        ProviderRegistry.remove(ID)
    }
}
