package com.ai.ui.helpers

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.Locale

/** [formatExportCents] / [formatExportSeconds] — the Locale-safe cost-table
 *  formatters (audit reports#40/52/53). Pin a COMMA-decimal default locale to
 *  prove they always emit a dot, regardless of the device locale. */
class ExportCostFormatTest {
    private val saved = Locale.getDefault()
    @Before fun pinCommaLocale() = Locale.setDefault(Locale.GERMANY)   // uses ',' as decimal sep
    @After fun restore() = Locale.setDefault(saved)

    @Test fun cents_use_two_decimals_with_a_dot_even_on_comma_locale() {
        assertThat(formatExportCents(1.23)).isEqualTo("1.23")
        assertThat(formatExportCents(0.0)).isEqualTo("0.00")
        assertThat(formatExportCents(1234.5)).isEqualTo("1234.50")
    }

    @Test fun cents_round_to_two_decimals() {
        assertThat(formatExportCents(1.234)).isEqualTo("1.23")
        assertThat(formatExportCents(1.236)).isEqualTo("1.24")
        assertThat(formatExportCents(1.999)).isEqualTo("2.00")
    }

    @Test fun seconds_one_decimal_with_a_dot() {
        assertThat(formatExportSeconds(1500L)).isEqualTo("1.5")
        assertThat(formatExportSeconds(500L)).isEqualTo("0.5")
        assertThat(formatExportSeconds(0L)).isEqualTo("0.0")
        assertThat(formatExportSeconds(65_000L)).isEqualTo("65.0")
    }
}
