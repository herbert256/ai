package com.ai.ui.shared

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UiFormattingTest {
    @Test fun formatCompactNumber_uses_expected_suffixes() {
        assertThat(formatCompactNumber(999)).isEqualTo("999")
        assertThat(formatCompactNumber(1_200)).isEqualTo("1.2K")
        assertThat(formatCompactNumber(2_500_000)).isEqualTo("2.5M")
        assertThat(formatCompactNumber(3_400_000_000)).isEqualTo("3.4B")
    }

    @Test fun formatTokenPricePerMillion_chooses_precision_by_magnitude() {
        assertThat(formatTokenPricePerMillion(0.000002)).isEqualTo("$2.00 / 1M tokens")
        assertThat(formatTokenPricePerMillion(0.0000001234)).isEqualTo("$0.1234 / 1M tokens")
        assertThat(formatTokenPricePerMillion(0.000000001234)).isEqualTo("$0.001234 / 1M tokens")
    }

    @Test fun money_and_decimal_helpers_use_requested_precision() {
        assertThat(formatUsd(0.123456, decimals = 3)).isEqualTo("$0.123")
        assertThat(formatCents(0.012345, decimals = 2)).isEqualTo("1.23")
        assertThat(formatDecimal(3.14159, decimals = 4)).isEqualTo("3.1416")
    }
}
