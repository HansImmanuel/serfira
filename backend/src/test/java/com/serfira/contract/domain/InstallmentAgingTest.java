package com.serfira.contract.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T6 — {@link InstallmentAging}, the aging calendar (ADR-013 A-4): OVERDUE starts on
 * {@code due_date + grace + 1} and {@code DPD = max(0, D − due_date − grace)}.
 *
 * <p>Fixture: the demo contract's period 1 is due 2026-02-28 with a 3-day grace period, so 2026-03-03 is the
 * last free day and 2026-03-04 is the first overdue day (DPD 1).
 */
class InstallmentAgingTest {

	private static final LocalDate DUE_DATE = LocalDate.of(2026, 2, 28);
	private static final int GRACE_DAYS = 3;

	@Test
	void theFirstOverdueDateIsTheDayAfterTheGraceWindow() {
		assertThat(InstallmentAging.firstOverdueDate(DUE_DATE, GRACE_DAYS)).isEqualTo(LocalDate.of(2026, 3, 4));
	}

	@Test
	void dpdIsZeroThroughTheGraceWindowThenCountsFromOne() {
		assertThat(InstallmentAging.daysPastDue(DUE_DATE, GRACE_DAYS, LocalDate.of(2026, 2, 27))).isZero();
		assertThat(InstallmentAging.daysPastDue(DUE_DATE, GRACE_DAYS, DUE_DATE)).isZero();
		assertThat(InstallmentAging.daysPastDue(DUE_DATE, GRACE_DAYS, LocalDate.of(2026, 3, 3))).isZero();
		assertThat(InstallmentAging.daysPastDue(DUE_DATE, GRACE_DAYS, LocalDate.of(2026, 3, 4))).isEqualTo(1);
		assertThat(InstallmentAging.daysPastDue(DUE_DATE, GRACE_DAYS, LocalDate.of(2026, 3, 5))).isEqualTo(2);
		assertThat(InstallmentAging.daysPastDue(DUE_DATE, GRACE_DAYS, LocalDate.of(2026, 3, 31))).isEqualTo(28);
	}

	@Test
	void withoutGraceTheDayAfterTheDueDateIsOverdue() {
		assertThat(InstallmentAging.firstOverdueDate(DUE_DATE, 0)).isEqualTo(LocalDate.of(2026, 3, 1));
		assertThat(InstallmentAging.daysPastDue(DUE_DATE, 0, DUE_DATE)).isZero();
		assertThat(InstallmentAging.daysPastDue(DUE_DATE, 0, LocalDate.of(2026, 3, 1))).isEqualTo(1);
	}

	@ParameterizedTest(name = "due {0} + grace {1} -> first overdue {2}")
	@CsvSource({
			// month-end due dates roll into the next month
			"2026-01-31, 3, 2026-02-04",
			"2026-04-30, 3, 2026-05-04",
			// February in a common year and in a leap year
			"2026-02-28, 0, 2026-03-01",
			"2028-02-28, 0, 2028-02-29",
			"2028-02-29, 3, 2028-03-04",
			"2028-02-27, 1, 2028-02-29",
			// year end
			"2026-12-31, 3, 2027-01-04"
	})
	void theFirstOverdueDateCrossesMonthAndYearBoundariesOnTheCalendar(LocalDate dueDate, int graceDays,
			LocalDate expectedFirstOverdue) {
		assertThat(InstallmentAging.firstOverdueDate(dueDate, graceDays)).isEqualTo(expectedFirstOverdue);
		assertThat(InstallmentAging.daysPastDue(dueDate, graceDays, expectedFirstOverdue.minusDays(1))).isZero();
		assertThat(InstallmentAging.daysPastDue(dueDate, graceDays, expectedFirstOverdue)).isEqualTo(1);
	}

	@Test
	void rejectsANegativeGracePeriodAndMissingDates() {
		assertThatThrownBy(() -> InstallmentAging.firstOverdueDate(DUE_DATE, -1))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> InstallmentAging.daysPastDue(DUE_DATE, -1, DUE_DATE))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> InstallmentAging.firstOverdueDate(null, GRACE_DAYS))
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> InstallmentAging.daysPastDue(DUE_DATE, GRACE_DAYS, null))
				.isInstanceOf(NullPointerException.class);
	}
}
