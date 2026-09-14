package com.serfira.contract.domain.schedule;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * B4 — due-date matrix. Pins the month-end clamping rule (31 → last valid day of the target month,
 * leap years included) with explicit expected dates.
 */
class ScheduleDueDateTest {

	@ParameterizedTest(name = "start {0} + period {1} => {2}")
	@CsvSource({
			// Core documented case: start 2026-01-31 → Feb 28 (not 31).
			"2026-01-31, 1, 2026-02-28",
			"2026-01-31, 2, 2026-03-31",
			"2026-01-31, 3, 2026-04-30",
			"2026-01-31, 4, 2026-05-31",
			"2026-01-31, 5, 2026-06-30",
			"2026-01-31, 6, 2026-07-31",
			"2026-01-31, 7, 2026-08-31",
			"2026-01-31, 8, 2026-09-30",
			"2026-01-31, 9, 2026-10-31",
			"2026-01-31, 10, 2026-11-30",
			"2026-01-31, 11, 2026-12-31",
			// Leap vs non-leap February.
			"2024-01-31, 1, 2024-02-29",
			"2023-01-31, 1, 2023-02-28",
			"2028-01-31, 1, 2028-02-29",
			"2000-01-31, 1, 2000-02-29",
			"1900-01-31, 1, 1900-02-28",
			// Start date itself on a leap day.
			"2024-02-29, 1, 2024-03-29",
			"2024-02-29, 12, 2025-02-28",
			"2025-02-28, 1, 2025-03-28",
			"2024-02-28, 1, 2024-03-28",
			// Other 30/31-day month-end clamping.
			"2026-04-30, 1, 2026-05-30",
			"2026-05-31, 1, 2026-06-30",
			"2026-08-31, 1, 2026-09-30",
			"2026-03-31, 1, 2026-04-30",
			"2026-03-31, 2, 2026-05-31",
			// Year boundary.
			"2026-12-31, 1, 2027-01-31",
			"2025-12-31, 1, 2026-01-31",
			// Non-clamping days stay unchanged.
			"2026-01-15, 1, 2026-02-15",
			"2026-01-15, 2, 2026-03-15",
			"2026-06-30, 1, 2026-07-30"
	})
	void dueDatesFollowMonthEndClamping(String start, int periodNo, String expected) {
		assertThat(ScheduleDueDate.of(LocalDate.parse(start), periodNo)).isEqualTo(LocalDate.parse(expected));
	}

	@Test
	void periodOneIsOneCalendarMonthAfterStart() {
		assertThat(ScheduleDueDate.of(LocalDate.of(2026, 1, 31), 1)).isEqualTo(LocalDate.of(2026, 2, 28));
		assertThat(ScheduleDueDate.of(LocalDate.of(2026, 1, 31), 12)).isEqualTo(LocalDate.of(2027, 1, 31));
	}

	@Test
	void periodNumberingIsOneBased() {
		assertThatIllegalArgumentException().isThrownBy(() -> ScheduleDueDate.of(LocalDate.of(2026, 1, 31), 0));
		assertThatIllegalArgumentException().isThrownBy(() -> ScheduleDueDate.of(LocalDate.of(2026, 1, 31), -3));
	}

	@Test
	void startDateIsRequired() {
		assertThatNullPointerException().isThrownBy(() -> ScheduleDueDate.of(null, 1));
	}
}