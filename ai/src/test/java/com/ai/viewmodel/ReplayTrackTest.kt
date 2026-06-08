package com.ai.viewmodel

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Job
import org.junit.Test

/**
 * Tests for the shared replay-track primitive (audit R03): the keyed state flow
 * + per-key job map plumbing the temperature / reasoning / web-search /
 * prompt-edit replay flows used to hand-roll in three places.
 */
class ReplayTrackTest {

    @Test fun set_get_drop_and_state_flow() {
        val t = ReplayTrack<String>()
        assertThat(t.get("k")).isNull()

        t.set("k", "running")
        assertThat(t.get("k")).isEqualTo("running")
        assertThat(t.states.value).containsExactly("k", "running")

        t.set("k", "done")            // replace
        assertThat(t.get("k")).isEqualTo("done")

        t.drop("k")
        assertThat(t.get("k")).isNull()
        assertThat(t.states.value).isEmpty()
    }

    @Test fun update_applies_only_when_present() {
        val t = ReplayTrack<Int>()
        t.update("k") { it + 1 }                 // absent → no-op
        assertThat(t.get("k")).isNull()

        t.set("k", 1)
        t.update("k") { it + 10 }
        assertThat(t.get("k")).isEqualTo(11)
    }

    @Test fun registerJob_self_removes_on_completion() {
        val t = ReplayTrack<String>()
        val job = Job()
        t.registerJob("k", job)
        assertThat(t.jobActive("k")).isTrue()

        job.complete()
        assertThat(t.jobActive("k")).isFalse()    // removed from the map
    }

    @Test fun cancelJob_cancels_job_but_keeps_state() {
        val t = ReplayTrack<String>()
        val job = Job()
        t.set("k", "running")
        t.registerJob("k", job)

        t.cancelJob("k")
        assertThat(job.isCancelled).isTrue()
        assertThat(t.get("k")).isEqualTo("running")   // state untouched
    }

    @Test fun cancel_cancels_job_and_drops_state() {
        val t = ReplayTrack<String>()
        val job = Job()
        t.set("k", "running")
        t.registerJob("k", job)

        t.cancel("k")
        assertThat(job.isCancelled).isTrue()
        assertThat(t.get("k")).isNull()
    }

    @Test fun cancelByPrefix_clears_matching_jobs_and_states_only() {
        val t = ReplayTrack<String>()
        val a = Job(); val b = Job(); val other = Job()
        t.set("r1|x", "ax"); t.registerJob("r1|x", a)
        t.set("r1|y", "ay"); t.registerJob("r1|y", b)
        t.set("r2|z", "bz"); t.registerJob("r2|z", other)

        t.cancelByPrefix("r1|")

        assertThat(a.isCancelled).isTrue()
        assertThat(b.isCancelled).isTrue()
        assertThat(other.isActive).isTrue()
        assertThat(t.states.value.keys).containsExactly("r2|z")
    }
}
