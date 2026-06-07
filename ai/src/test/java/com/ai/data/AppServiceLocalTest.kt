package com.ai.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The synthetic LOCAL provider sentinel + its Gson round-trip through
 *  [AppServiceAdapter] (which routes via findById so LOCAL resolves). */
class AppServiceLocalTest {

    @Test fun local_sentinel_has_id_Local() {
        assertThat(AppService.LOCAL.id).isEqualTo("Local")
    }

    @Test fun findById_resolves_local_without_the_registry() {
        assertThat(AppService.findById("Local")).isEqualTo(AppService.LOCAL)
    }

    @Test fun adapter_serializes_as_bare_id_string() {
        val json = createAppGson().toJson(AppService.LOCAL, AppService::class.java)
        assertThat(json).isEqualTo("\"Local\"")
    }

    @Test fun adapter_round_trips_local() {
        val gson = createAppGson()
        val back = gson.fromJson(gson.toJson(AppService.LOCAL, AppService::class.java), AppService::class.java)
        assertThat(back).isEqualTo(AppService.LOCAL)
    }

    @Test fun unknown_provider_id_deserializes_to_a_synthetic_not_a_throw() {
        // A removed/renamed custom provider id must not blow up the whole
        // embedding object — it resolves to a synthetic AppService with the id.
        val svc = createAppGson().fromJson("\"REMOVED_PROVIDER_X\"", AppService::class.java)
        assertThat(svc.id).isEqualTo("REMOVED_PROVIDER_X")
    }
}
