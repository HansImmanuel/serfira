package com.serfira.reporting.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T8 — bucket classification at every boundary (ADR-013 A-7, 06_FRONTEND_SPEC.md §2.9). The DPD-start
 * definition (A-4) makes DPD = 0 "Current" and DPD = 1 the first day in the 1–30 band, so the band edges
 * are the interesting cases.
 */
class AgingBucketTest {

	@ParameterizedTest(name = "DPD {0} -> {1}")
	@CsvSource({
			"0, CURRENT",
			"1, DPD_1_30",
			"30, DPD_1_30",
			"31, DPD_31_60",
			"60, DPD_31_60",
			"61, DPD_61_90",
			"90, DPD_61_90",
			"91, DPD_OVER_90",
			"365, DPD_OVER_90"
	})
	void classifiesEachDaysPastDueToItsBucket(int daysPastDue, AgingBucket expected) {
		assertThat(AgingBucket.of(daysPastDue)).isEqualTo(expected);
	}

	@Test
	void rejectsNegativeDaysPastDue() {
		assertThatThrownBy(() -> AgingBucket.of(-1)).isInstanceOf(IllegalArgumentException.class);
	}
}
