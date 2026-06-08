package com.ai.viewmodel

import com.ai.data.ProviderThrottle
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.sync.Semaphore
import org.junit.Test

/**
 * [PermitHold] is the deadlock-prevention core of the throttled-batch path
 * (audit T03 / P06): it owns one item's three nested permits (sub-cap → global
 * → per-host) and must (a) release all three on a 429/529 backoff yield and
 * re-take them in canonical order, and (b) never double-release when a
 * cancellation `dispose()` races a mid-flight `yieldFor()`.
 *
 * These tests use a blank host (`""`), so `ProviderThrottle.acquire("")` and the
 * injected `Releaser(null)` are genuine no-ops — the sub-cap and global
 * semaphores are real and asserted on directly, touching no global state.
 *
 * A `Semaphore(permits = 1, acquiredPermits = 1)` models a permit that the
 * caller already acquired and handed to the hold: availablePermits starts at 0,
 * and a correct release brings it back to 1 exactly once.
 */
class PermitHoldTest {

    private fun heldHold(): Triple<PermitHold, Semaphore, Semaphore> {
        val subCap = Semaphore(permits = 1, acquiredPermits = 1)
        val global = Semaphore(permits = 1, acquiredPermits = 1)
        val hold = PermitHold(subCap, global, host = "", hostReleaser = ProviderThrottle.Releaser(null))
        return Triple(hold, subCap, global)
    }

    @Test fun dispose_releases_both_cap_permits_exactly_once() {
        val (hold, subCap, global) = heldHold()
        assertThat(subCap.availablePermits).isEqualTo(0)
        assertThat(global.availablePermits).isEqualTo(0)

        hold.dispose()

        assertThat(subCap.availablePermits).isEqualTo(1)
        assertThat(global.availablePermits).isEqualTo(1)
    }

    @Test fun dispose_is_idempotent_and_does_not_over_release() {
        val (hold, subCap, global) = heldHold()

        hold.dispose()
        hold.dispose()
        hold.dispose()

        // Still exactly 1 — a second/third dispose must not inflate the cap.
        assertThat(subCap.availablePermits).isEqualTo(1)
        assertThat(global.availablePermits).isEqualTo(1)
    }

    @Test fun yieldFor_releases_all_then_reacquires_in_order_net_zero() {
        val (hold, subCap, global) = heldHold()

        hold.yieldFor(0L)

        // Released all three, slept, then re-acquired sub → global → host.
        // Net effect on the caps: still held (0 available).
        assertThat(subCap.availablePermits).isEqualTo(0)
        assertThat(global.availablePermits).isEqualTo(0)

        // And the hold is still live: a subsequent dispose releases once.
        hold.dispose()
        assertThat(subCap.availablePermits).isEqualTo(1)
        assertThat(global.availablePermits).isEqualTo(1)
    }

    @Test fun dispose_after_yieldFor_releases_exactly_once() {
        val (hold, subCap, global) = heldHold()

        hold.yieldFor(0L)
        hold.dispose()

        assertThat(subCap.availablePermits).isEqualTo(1)
        assertThat(global.availablePermits).isEqualTo(1)
    }

    @Test fun yieldFor_after_dispose_is_a_noop_no_double_release() {
        val (hold, subCap, global) = heldHold()

        hold.dispose()
        // A backoff yield that lands after the item was already disposed must
        // not release the (now freed) permits a second time.
        hold.yieldFor(0L)

        assertThat(subCap.availablePermits).isEqualTo(1)
        assertThat(global.availablePermits).isEqualTo(1)
    }

    @Test fun repeated_yield_then_dispose_keeps_caps_balanced() {
        val (hold, subCap, global) = heldHold()

        hold.yieldFor(0L)
        hold.yieldFor(0L)
        hold.yieldFor(0L)
        hold.dispose()

        assertThat(subCap.availablePermits).isEqualTo(1)
        assertThat(global.availablePermits).isEqualTo(1)
    }
}
