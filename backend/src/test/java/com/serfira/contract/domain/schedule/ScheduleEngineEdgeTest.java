package com.serfira.contract.domain.schedule;

import com.serfira.contract.domain.InterestScheme;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * B2/B3 — engine guards and structural invariants across both interest schemes.
 */
class ScheduleEngineEdgeTest {

	private final ScheduleEngine engine = new ScheduleEngine();

	@Test
	void inputsAreValidated() {
		assertThatNullPointerException()
				.isThrownBy(() -> engine.generate(null, new BigDecimal("0.0150"), LocalDate.of(2026, 1, 31), 12, InterestScheme.FLAT));
		assertThatNullPointerException()
				.isThrownBy(() -> engine.generate(new BigDecimal("1000"), null, LocalDate.of(2026, 1, 31), 12, InterestScheme.FLAT));
		assertThatNullPointerException()
				.isThrownBy(() -> engine.generate(new BigDecimal("1000"), new BigDecimal("0.0150"), null, 12, InterestScheme.FLAT));
		assertThatNullPointerException()
				.isThrownBy(() -> engine.generate(new BigDecimal("1000"), new BigDecimal("0.0150"), LocalDate.of(2026, 1, 31), 12, null));
		assertThatIllegalArgumentException()
				.isThrownBy(() -> engine.generate(BigDecimal.ZERO, new BigDecimal("0.0150"), LocalDate.of(2026, 1, 31), 12, InterestScheme.FLAT));
		assertThatIllegalArgumentException()
				.isThrownBy(() -> engine.generate(new BigDecimal("-1"), new BigDecimal("0.0150"), LocalDate.of(2026, 1, 31), 12, InterestScheme.FLAT));
		assertThatIllegalArgumentException()
				.isThrownBy(() -> engine.generate(new BigDecimal("1000"), new BigDecimal("-0.0001"), LocalDate.of(2026, 1, 31), 12, InterestScheme.FLAT));
		assertThatIllegalArgumentException()
				.isThrownBy(() -> engine.generate(new BigDecimal("1000"), new BigDecimal("0.0150"), LocalDate.of(2026, 1, 31), 0, InterestScheme.FLAT));
	}

	@Test
	void effectiveZeroInterestSplitsPrincipalEqually() {
		List<ScheduleLine> schedule = engine.generate(
				new BigDecimal("1200000"), BigDecimal.ZERO, LocalDate.of(2026, 1, 31), 12, InterestScheme.EFFECTIVE);

		assertThat(schedule).hasSize(12);
		for (int i = 0; i < 11; i++) {
			assertThat(schedule.get(i).principalAmount()).isEqualByComparingTo("100000.00");
			assertThat(schedule.get(i).interestAmount()).isEqualByComparingTo("0.00");
		}
		assertThat(schedule.get(11).principalAmount()).isEqualByComparingTo("100000.00");
		assertThat(schedule.get(11).interestAmount()).isEqualByComparingTo("0.00");
	}

	@Test
	void effectiveSinglePeriodIsPrincipalPlusOnePeriodInterest() {
		List<ScheduleLine> schedule = engine.generate(
				new BigDecimal("1000000"), new BigDecimal("0.0150"), LocalDate.of(2026, 1, 31), 1, InterestScheme.EFFECTIVE);

		assertThat(schedule).hasSize(1);
		assertThat(schedule.get(0).principalAmount()).isEqualByComparingTo("1000000.00");
		assertThat(schedule.get(0).interestAmount()).isEqualByComparingTo("15000.00");
	}

	@Test
	void flatSchedulePreservesTotalPrincipalAndTotalInterest() {
		List<ScheduleLine> schedule = engine.generate(
				new BigDecimal("12345678.90"), new BigDecimal("0.0150"), LocalDate.of(2026, 1, 31), 17, InterestScheme.FLAT);

		assertThat(sum(schedule, ScheduleLine::principalAmount)).isEqualByComparingTo("12345678.90");
		assertThat(sum(schedule, ScheduleLine::interestAmount))
				.isEqualByComparingTo(new BigDecimal("12345678.90").multiply(new BigDecimal("0.0150"))
						.multiply(BigDecimal.valueOf(17)).setScale(2, java.math.RoundingMode.HALF_EVEN));
	}

	@Test
	void effectiveSchedulePreservesTotalPrincipalAndLastPeriodAbsorbsResidual() {
		List<ScheduleLine> schedule = engine.generate(
				new BigDecimal("10000000"), new BigDecimal("0.0100"), LocalDate.of(2026, 1, 31), 24, InterestScheme.EFFECTIVE);

		assertThat(sum(schedule, ScheduleLine::principalAmount)).isEqualByComparingTo("10000000.00");
		// No period may carry a negative principal; the last one is the residual (<= nominal payment).
		assertThat(schedule).allSatisfy(line -> assertThat(line.principalAmount()).isNotNegative());
		assertThat(schedule.get(23).principalAmount())
				.isLessThanOrEqualTo(schedule.get(0).principalAmount().add(schedule.get(0).interestAmount()));
	}

	@Test
	void effectivePrincipalIncreasesAndInterestDecreases() {
		List<ScheduleLine> schedule = engine.generate(
				new BigDecimal("10000000"), new BigDecimal("0.0075"), LocalDate.of(2026, 1, 31), 24, InterestScheme.EFFECTIVE);

		for (int i = 1; i < schedule.size(); i++) {
			assertThat(schedule.get(i).principalAmount()).isGreaterThan(schedule.get(i - 1).principalAmount());
			assertThat(schedule.get(i).interestAmount()).isLessThan(schedule.get(i - 1).interestAmount());
		}
	}

	@Test
	void schedulesSpanStartDatePlusOneThroughPlusTenorMonths() {
		LocalDate start = LocalDate.of(2026, 1, 31);
		List<ScheduleLine> schedule = engine.generate(
				new BigDecimal("8000000"), new BigDecimal("0.0150"), start, 11, InterestScheme.FLAT);

		assertThat(schedule).extracting(ScheduleLine::dueDate)
				.containsExactly(
						LocalDate.of(2026, 2, 28), LocalDate.of(2026, 3, 31), LocalDate.of(2026, 4, 30),
						LocalDate.of(2026, 5, 31), LocalDate.of(2026, 6, 30), LocalDate.of(2026, 7, 31),
						LocalDate.of(2026, 8, 31), LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 31),
						LocalDate.of(2026, 11, 30), LocalDate.of(2026, 12, 31));
	}

	private BigDecimal sum(List<ScheduleLine> schedule, java.util.function.Function<ScheduleLine, BigDecimal> extractor) {
		return schedule.stream().map(extractor).reduce(BigDecimal.ZERO, BigDecimal::add);
	}
}