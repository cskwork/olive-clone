package com.olive.commerce.batch;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class DailySalesSummaryTest {

    @Test
    void replaceAmounts_overwritesPriorAggregationResult() {
        DailySalesSummary summary = DailySalesSummary.create(LocalDate.of(2026, 5, 15), null, null, null);
        summary.addAmounts(2, new BigDecimal("10000.00"));

        summary.replaceAmounts(2, new BigDecimal("10000.00"));
        summary.replaceAmounts(2, new BigDecimal("10000.00"));

        assertThat(summary.getOrderCount()).isEqualTo(2);
        assertThat(summary.getTotalSalesAmount()).isEqualByComparingTo("10000.00");
    }
}
