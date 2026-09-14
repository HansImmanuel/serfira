package com.serfira.contract.domain.schedule;

import com.serfira.contract.domain.InterestScheme;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B3 — EFFECTIVE (annuity) golden tests. Expected values are generated at DECIMAL128 precision and locked
 * after independent verification (Addendum §18.2 pins month-1 interest = 1,125,000.00; TECH SPEC §4.2
 * defines the formula). Month-1 interest of every case is computed by hand: P × i exactly.
 */
class EffectiveScheduleGoldenTest {

	private final ScheduleEngine engine = new ScheduleEngine();

	@Test
	void demoContractBGoldenCase() {
		// Addendum §18.2: principal 150,000,000, tenor 36, effective 0.75%/month, start 2026-01-15.
		List<ScheduleLine> schedule = engine.generate(
				new BigDecimal("150000000"), new BigDecimal("0.0075"), LocalDate.of(2026, 1, 15), 36, InterestScheme.EFFECTIVE);

		assertThat(schedule).hasSize(36);
		assertThat(schedule.get(0).dueDate()).isEqualTo(LocalDate.of(2026, 2, 15));
		// Nominal constant payment A = P·i·(1+i)^n / ((1+i)^n − 1) at DECIMAL128, rounded HALF_EVEN.
		assertThat(schedule.get(0).totalAmount()).isEqualByComparingTo("4769959.90");
		// Month-1 interest = 150,000,000 × 0.0075 exactly.
		assertLine(schedule.get(0), 1, "2026-02-15", "3644959.90", "1125000.00");

		// Payment nominal is constant for periods 1..n−1.
		for (int i = 1; i < 35; i++) {
			assertThat(schedule.get(i).principalAmount().add(schedule.get(i).interestAmount()))
					.isEqualByComparingTo("4769959.90");
		}

		// Last period absorbs the residual principal (TECH SPEC §4.2): total may differ from the nominal.
		assertLine(schedule.get(35), 36, "2029-01-15", "4734451.45", "35508.39");
		assertThat(schedule.get(35).totalAmount()).isEqualByComparingTo("4769959.84");

		assertThat(sumPrincipal(schedule)).isEqualByComparingTo("150000000.00");
		assertThat(sumInterest(schedule)).isEqualByComparingTo("21718556.34");
	}

	@Test
	void smallAnnuityGoldenCase() {
		// P = 1,000,000, i = 0.01, n = 12 → A = 88,848.79; month-1 interest = 10,000.00.
		List<ScheduleLine> schedule = engine.generate(
				new BigDecimal("1000000"), new BigDecimal("0.01"), LocalDate.of(2026, 1, 31), 12, InterestScheme.EFFECTIVE);

		assertThat(schedule).hasSize(12);
		assertLine(schedule.get(0), 1, "2026-02-28", "78848.79", "10000.00");
		for (int i = 1; i < 11; i++) {
			assertThat(schedule.get(i).principalAmount().add(schedule.get(i).interestAmount()))
					.isEqualByComparingTo("88848.79");
		}
		assertLine(schedule.get(11), 12, "2027-01-31", "87969.07", "879.69");
		assertThat(schedule.get(11).totalAmount()).isEqualByComparingTo("88848.76");

		assertThat(sumPrincipal(schedule)).isEqualByComparingTo("1000000.00");
		assertThat(sumInterest(schedule)).isEqualByComparingTo("66185.45");
	}

	@Test
	void annuityPropertiesHold() {
		List<ScheduleLine> schedule = engine.generate(
				new BigDecimal("150000000"), new BigDecimal("0.0075"), LocalDate.of(2026, 1, 15), 36, InterestScheme.EFFECTIVE);

		assertThat(sumPrincipal(schedule)).isEqualByComparingTo("150000000.00");
		// Annuity property: principal share grows each period, interest share shrinks.
		for (int i = 1; i < schedule.size(); i++) {
			assertThat(schedule.get(i).principalAmount()).isGreaterThan(schedule.get(i - 1).principalAmount());
			assertThat(schedule.get(i).interestAmount()).isLessThan(schedule.get(i - 1).interestAmount());
		}
	}

	private void assertLine(ScheduleLine line, int periodNo, String dueDate, String principal, String interest) {
		assertThat(line.periodNo()).isEqualTo(periodNo);
		assertThat(line.dueDate()).isEqualTo(LocalDate.parse(dueDate));
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