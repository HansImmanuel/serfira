package com.serfira.reporting.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T8 — the breakdown accumulates per bucket and keeps {@code Σ bucket = total} by construction
 * (ADR-013 A-7).
 */
class AgingBreakdownTest {

	@Test
	void startsEmptyWithZeroInEveryBucket() {
		AgingBreakdown breakdown = new AgingBreakdown();

		for (AgingBucket bucket : AgingBucket.values()) {
			assertThat(breakdown.amount(bucket)).isEqualByComparingTo("0.00");
		}
		assertThat(breakdown.total()).isEqualByComparingTo("0.00");
	}

	@Test
	void addsOutstandingToTheBucketItsDaysPastDueSelectsAndSumsToTheTotal() {
		AgingBreakdown breakdown = new AgingBreakdown();

		breakdown.add(0, new BigDecimal("100.00"));   // Current
		breakdown.add(15, new BigDecimal("200.00"));  // 1-30
		breakdown.add(30, new BigDecimal("50.00"));   // 1-30
		breakdown.add(45, new BigDecimal("300.00"));  // 31-60
		breakdown.add(91, new BigDecimal("400.00"));  // >90

		assertThat(breakdown.amount(AgingBucket.CURRENT)).isEqualByComparingTo("100.00");
		assertThat(breakdown.amount(AgingBucket.DPD_1_30)).isEqualByComparingTo("250.00");
		assertThat(breakdown.amount(AgingBucket.DPD_31_60)).isEqualByComparingTo("300.00");
		assertThat(breakdown.amount(AgingBucket.DPD_61_90)).isEqualByComparingTo("0.00");
		assertThat(breakdown.amount(AgingBucket.DPD_OVER_90)).isEqualByComparingTo("400.00");
		assertThat(breakdown.total()).isEqualByComparingTo("1050.00");
	}

	@Test
	void addAllFoldsAnotherBreakdownBucketByBucket() {
		AgingBreakdown contractA = new AgingBreakdown();
		contractA.add(0, new BigDecimal("100.00"));
		contractA.add(45, new BigDecimal("300.00"));
		AgingBreakdown contractB = new AgingBreakdown();
		contractB.add(45, new BigDecimal("200.00"));
		contractB.add(91, new BigDecimal("400.00"));

		AgingBreakdown portfolio = new AgingBreakdown();
		portfolio.addAll(contractA);
		portfolio.addAll(contractB);

		assertThat(portfolio.amount(AgingBucket.CURRENT)).isEqualByComparingTo("100.00");
		assertThat(portfolio.amount(AgingBucket.DPD_31_60)).isEqualByComparingTo("500.00");
		assertThat(portfolio.amount(AgingBucket.DPD_OVER_90)).isEqualByComparingTo("400.00");
		assertThat(portfolio.total()).isEqualByComparingTo("1000.00");
	}

	@Test
	void rejectsNonPositiveOutstanding() {
		AgingBreakdown breakdown = new AgingBreakdown();

		assertThatThrownBy(() -> breakdown.add(5, BigDecimal.ZERO)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> breakdown.add(5, new BigDecimal("-1.00")))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
