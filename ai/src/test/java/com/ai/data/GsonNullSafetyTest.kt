package com.ai.data

import com.ai.model.Settings
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** [createAppGson]'s NullSafeFieldAdapterFactory coerces a *missing* collection
 *  field (Gson's UnsafeAllocator bypasses the constructor + its defaults) to an
 *  empty collection instead of leaving it null — guarding the `!= null` and
 *  iteration call sites. */
class GsonNullSafetyTest {
    private val gson = createAppGson()

    @Test fun missing_collection_fields_coerce_to_empty_not_null() {
        val s = gson.fromJson("{}", Settings::class.java)
        assertThat(s.agents).isEmpty()
        assertThat(s.flocks).isEmpty()
        assertThat(s.swarms).isEmpty()
        assertThat(s.providers).isEmpty()
    }

    @Test fun explicit_empty_list_round_trips() {
        val s = gson.fromJson("{\"agents\":[],\"swarms\":[]}", Settings::class.java)
        assertThat(s.agents).isEmpty()
        assertThat(s.swarms).isEmpty()
    }

    @Test fun present_list_is_preserved() {
        val json = "{\"systemPrompts\":[{\"id\":\"sp1\",\"name\":\"N\",\"prompt\":\"P\"}]}"
        val s = gson.fromJson(json, Settings::class.java)
        assertThat(s.systemPrompts.map { it.id }).containsExactly("sp1")
    }
}
