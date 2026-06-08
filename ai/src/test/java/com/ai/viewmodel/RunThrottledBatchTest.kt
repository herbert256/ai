package com.ai.viewmodel

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Orchestration contract for [runThrottledBatch] (audit T03). These cover the
 * deterministic, global-state-light cases: empty input is a no-op, every item
 * is registered before it starts (the cancel-before-delete race fix), and a
 * fixed-host item whose host resolves to null is skipped (no body) while still
 * being registered. Permit-ordering / backoff is covered by PermitHoldTest.
 *
 * `interleaveByHost` shuffles within host groups, so assertions are on sets,
 * not order.
 */
class RunThrottledBatchTest {

    // NB: block bodies (not `= runBlocking {…}`) so each @Test returns Unit —
    // an expression body would surface Truth's `Ordered` return and JUnit4
    // rejects non-void test methods.

    @Test fun empty_items_never_runs_body() {
        runBlocking {
            var bodyCalls = 0
            runThrottledBatch(
                items = emptyList<String>(),
                hostOf = { "h" },
                subCap = Semaphore(10),
                body = { bodyCalls++ },
            )
            assertThat(bodyCalls).isEqualTo(0)
        }
    }

    @Test fun fixed_host_skips_null_host_items_but_registers_every_item() {
        runBlocking {
            val registered = ConcurrentLinkedQueue<String>()
            val bodied = ConcurrentLinkedQueue<String>()

            runThrottledBatch(
                items = listOf("a", "b", "c"),
                // "b" has no host → must be skipped (no body), but still registered.
                hostOf = { if (it == "b") null else "unit-host-rtb" },
                subCap = Semaphore(10),
                register = { item, _ -> registered += item },
                body = { bodied += it },
            )

            assertThat(registered.toSet()).containsExactly("a", "b", "c")
            assertThat(bodied.toSet()).containsExactly("a", "c")
        }
    }

    @Test fun every_item_is_registered_before_its_body_runs() {
        runBlocking {
            // register() must fire for an item before that item's body — the
            // deferred is recorded into the cancellation map before start() so a
            // delete racing the launch can still cancel it.
            val registeredBeforeBody = ConcurrentLinkedQueue<Boolean>()
            val registered = HashSet<String>()

            runThrottledBatch(
                items = listOf("x", "y"),
                hostOf = { "unit-host-rtb2" },
                subCap = Semaphore(10),
                register = { item, _ -> registered += item },
                body = { registeredBeforeBody += registered.contains(it) },
            )

            assertThat(registeredBeforeBody.toList()).containsExactly(true, true)
        }
    }
}
