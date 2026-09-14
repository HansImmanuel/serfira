package com.serfira.contract.domain.schedule;

import com.serfira.contract.domain.InterestScheme;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B2 — FLAT golden tests. Expected values are calculated manually/spreadsheet-style and locked so the
 * schedule engine can be refactored without silently changing the produced numbers (Sprint Plan rule 1).
 *
 * <p>Golden case 1: 03_DOMAIN_MODEL.md §4 — plafon 8,000,000, tenor 11, flat 1.5%/month (0.0150),
 * start 2026-01-31.
 */
class FlatScheduleGoldenTest {

	private final ScheduleEngine engine = new ScheduleEngine();

	@Test
	void domainModelGoldenCase() {
		List<ScheduleLine> schedule = engine.generate(
				new BigDecimal("8000000"), new BigDecimal("0.0150"), LocalDate.of(2026, 1, 31), 11, InterestScheme.FLAT);

		assertThat(schedule).hasSize(11);
		assertLine(schedule.get(0), 1, "2026-02-28", "727272.73", "120000.00");
		assertLine(schedule.get(1), 2, "2026-03-31", "727272.73", "120000.00");
		assertLine(schedule.get(2), 3, "2026-04-30", "727272.73", "120000.00");
		assertLine(schedule.get(3), 4, "2026-05-31", "727272.73", "120000.00");
		assertLine(schedule.get(4), 5, "2026-06-30", "727272.73", "120000.00");
		assertLine(schedule.get(5), 6, "2026-07-31", "727272.73", "120000.00");
		assertLine(schedule.get(6), 7, "2026-08-31", "727272.73", "120000.00");
		assertLine(schedule.get(7), 8, "2026-09-30", "727272.73", "120000.00");
		assertLine(schedule.get(8), 9, "2026-10-31", "727272.73", "120000.00");
		assertLine(schedule.get(9), 10, "2026-11-30", "727272.73", "120000.00");
		// Last period absorbs the rounding residual: 8,000,000 − 10 × 727,272.73 = 727,272.70.
		assertLine(schedule.get(10), 11, "2026-12-31", "727272.70", "120000.00");

		assertThat(schedule).extracting(ScheduleLine::totalAmount)
				.contains(new BigDecimal("847272.73"), new BigDecimal("847272.70"));
		assertThat(schedule).extracting(ScheduleLine::totalAmount)
				.filteredOn(total -> total.equals(new BigDecimal("847272.73")))
				.hasSize(10);
	}

	@Test
	void demoContractAGoldenCase() {
		// Addendum §18.1: principal 16,000,000, tenor 12, flat 0.0150.
		List<ScheduleLine> schedule = engine.generate(
				new BigDecimal("16000000"), new BigDecimal("0.0150"), LocalDate.of(2026, 1, 31), 12, InterestScheme.FLAT);

		assertThat(schedule).hasSize(12);
		// interest total = 16,000,000 × 0.0150 × 12 = 2,880,000 → 240,000/month.
		// principal = 16,000,000/12 = 1,333,333.33; last = 16,000,000 − 11 × 1,333,333.33 = 1,333,333.37.
		for (int i = 0; i < 11; i++) {
			assertLine(schedule.get(i), i + 1, null, "1333333.33", "240000.00");
		}
		assertLine(schedule.get(11), 12, null, "1333333.37", "240000.00");

		assertThat(sumPrincipal(schedule)).isEqualByComparingTo("16000000.00");
		assertThat(sumInterest(schedule)).isEqualByComparingTo("2880000.00");
	}

	@Test
	void zeroInterestFlatScheduleSpreadsPrincipalOnly() {
		List<ScheduleLine> schedule = engine.generate(
				new BigDecimal("1200000"), BigDecimal.ZERO, LocalDate.of(2026, 1, 31), 12, InterestScheme.FLAT);

		assertThat(schedule).hasSize(12);
		for (int i = 0; i < 11; i++) {
			assertThat(schedule.get(i).principalAmount()).isEqualByComparingTo("100000.00");
			assertThat(schedule.get(i).interestAmount()).isEqualByComparingTo("0.00");
		}
		assertThat(schedule.get(11).principalAmount()).isEqualByComparingTo("100000.00");
		assertThat(schedule.get(11).interestAmount()).isEqualByComparingTo("0.00");
	}

	@Test
	void singlePeriodFlatScheduleIsPrincipalPlusOnePeriodInterest() {
		List<ScheduleLine> schedule = engine.generate(
				new BigDecimal("1000000"), new BigDecimal("0.0150"), LocalDate.of(2026, 1, 31), 1, InterestScheme.FLAT);

		assertThat(schedule).hasSize(1);
		assertLine(schedule.get(0), 1, "2026-02-28", "1000000.00", "15000.00");
	}

	@Test
	void halfEvenRoundingIsAppliedToPerPeriodValues() {
		// 1,000.04 / 8 = 125.005 → HALF_EVEN rounds toward the even cent → 125.00.
		List<ScheduleLine> even = engine.generate(
				new BigDecimal("1000.04"), BigDecimal.ZERO, LocalDate.of(2026, 1, 31), 8, InterestScheme.FLAT);
		for (int i = 0; i < 7; i++) {
			assertThat(even.get(i).principalAmount()).isEqualByComparingTo("125.00");
		}
		assertThat(even.get(7).principalAmount()).isEqualByComparingTo("125.04");

		// 1,000.12 / 8 = 125.015 → HALF_EVEN rounds toward the even cent → 125.02.
		List<ScheduleLine> odd = engine.generate(
				new BigDecimal("1000.12"), BigDecimal.ZERO, LocalDate.of(2026, 1, 31), 8, InterestScheme.FLAT);
		for (int i = 0; i < 7; i++) {
			assertThat(odd.get(i).principalAmount()).isEqualByComparingTo("125.02");
		}
		assertThat(odd.get(7).principalAmount()).isEqualByComparingTo("124.98");
	}

	private void assertLine(ScheduleLine line, int periodNo, String dueDate, String principal, String interest) {
		assertThat(line.periodNo()).isEqualTo(periodNo);
		if (dueDate != null) {
			assertThat(line.dueDate()).isEqualTo(LocalDate.parse(dueDate));
		}
		assertThat(line.principalAmount()).isEqualByComparingTo(principal);
		assertThat(line.interestAmount()).isEqualByComparingTo(interest);
	}

	private BigDecimal sumPrincipal(List<ScheduleLine> schedule) {
		return schedule.stream().map(ScheduleLine::principalAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
	}

	private BigDecimal sumInterest(List<ScheduleLine> schedule) {
		return schedule.stream().map(ScheduleLine::interestAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
	}
}