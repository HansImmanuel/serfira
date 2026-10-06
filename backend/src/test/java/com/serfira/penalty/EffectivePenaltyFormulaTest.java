package com.serfira.penalty;

import com.serfira.penalty.application.EffectivePenaltyService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Golden tests for the invariant-9 effective-penalty formula (ADR-019 D1): pure Java, no Spring, exercising
 * {@link EffectivePenaltyService#effective(BigDecimal, BigDecimal)} with hardcoded expected values.
 *
 * <p>{@code effective = max(0, grossAccrued − Σ adjustment)} — the same subtraction the V3/V15 DB caps use,
 * which is what the port-parity integration test confirms at the database level.
 */
class EffectivePenaltyFormulaTest {

	@Test
	void zeroAdjustmentLeavesTheGrossUnchanged() {
		assertThat(EffectivePenaltyService.effective(new BigDecimal("3146.66"), new BigDecimal("0.00")))
				.isEqualByComparingTo("3146.66");
	}

	@Test
	void aReductionLowersEffectiveByItsAmount() {
		// gross 3146.66 − reduce 1000.00 = 2146.66
		assertThat(EffectivePenaltyService.effective(new BigDecimal("3146.66"), new BigDecimal("1000.00")))
				.isEqualByComparingTo("2146.66");
	}

	@Test
	void aFullWaiverLeavesExactlyZero() {
		// gross 3146.66 − waive 3146.66 = 0.00 (not negative)
		assertThat(EffectivePenaltyService.effective(new BigDecimal("3146.66"), new BigDecimal("3146.66")))
				.isEqualByComparingTo("0.00");
	}

	@Test
	void anOverAdjustmentClampsToZeroAndNeverGoesNegative() {
		// The guard: Σ adjustment > gross must not drive effective below 0 (invariant 9). The DB caps reject
		// this write; the formula still clamps defensively.
		BigDecimal effective = EffectivePenaltyService.effective(new BigDecimal("3146.66"),
				new BigDecimal("5000.00"));
		assertThat(effective).isEqualByComparingTo("0.00");
		assertThat(effective.signum()).isNotNegative();
	}

	@Test
	void resultIsScaleTwo() {
		assertThat(EffectivePenaltyService.effective(new BigDecimal("100.00"), new BigDecimal("40.00")).scale())
				.isEqualTo(2);
	}
}
