package com.ai.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** [creditOrSpendingLimitExhausted] — decides whether a 429 is a billing /
 *  out-of-credits error (→ bench 6h) vs a transient rate limit (→ retry).
 *  Audit data#30: loose phrases that also appear in rate-limit bodies must NOT
 *  trigger the bench. */
class CreditLimitExhaustedTest {

    @Test fun structured_insufficient_quota_type_is_billing() {
        assertThat(creditOrSpendingLimitExhausted("{\"error\":{\"type\":\"insufficient_quota\"}}")).isTrue()
        assertThat(creditOrSpendingLimitExhausted("{\"error\":{\"code\":\"insufficient_quota\"}}")).isTrue()
    }

    @Test fun unambiguous_billing_phrases_are_billing() {
        assertThat(creditOrSpendingLimitExhausted("You have hit your monthly spending limit.")).isTrue()
        assertThat(creditOrSpendingLimitExhausted("insufficient balance")).isTrue()
        assertThat(creditOrSpendingLimitExhausted("You are out of credits")).isTrue()
        assertThat(creditOrSpendingLimitExhausted("credit balance is too low")).isTrue()
    }

    @Test fun rate_limit_wording_is_NOT_billing() {
        // The bug: these used to bench for 6h. They're transient rate limits.
        assertThat(creditOrSpendingLimitExhausted("Rate limit reached. Please see your billing details.")).isFalse()
        assertThat(creditOrSpendingLimitExhausted("You exceeded your current quota, slow down.")).isFalse()
        assertThat(creditOrSpendingLimitExhausted("429 Too Many Requests")).isFalse()
    }

    @Test fun null_or_empty_is_not_billing() {
        assertThat(creditOrSpendingLimitExhausted(null)).isFalse()
        assertThat(creditOrSpendingLimitExhausted("")).isFalse()
    }
}
