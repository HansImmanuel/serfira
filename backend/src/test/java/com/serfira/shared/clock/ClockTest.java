package com.serfira.shared.clock;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class ClockTest {

	@Test
	void systemClockIsAnchoredToAsiaJakarta() {
		SystemClock clock = new SystemClock();
		assertThat(clock.zone().getId()).isEqualTo("Asia/Jakarta");
		assertThat(clock.now().getOffset()).isEqualTo(ZoneOffset.ofHours(7));
	}

	@Test
	void fixedClockReturnsTheConfiguredInstant() {
		OffsetDateTime at = OffsetDateTime.of(2026, 9, 12, 10, 30, 0, 0, ZoneOffset.ofHours(7));
		FixedClock clock = new FixedClock(at);

		assertThat(clock.now()).isEqualTo(at);
		assertThat(clock.today()).isEqualTo(LocalDate.of(2026, 9, 12));
		assertThat(clock.instant()).isEqualTo(at.toInstant());
	}

	@Test
	void fixedClockCanAdvanceDeterministically() {
		FixedClock clock = new FixedClock(LocalDate.of(2026, 1, 31));
		clock.advanceBy(Duration.ofDays(1));
		assertThat(clock.today()).isEqualTo(LocalDate.of(2026, 2, 1));
	}

	@Test
	void fixedClockFromDateStartsAtMidnightInJakarta() {
		FixedClock clock = new FixedClock(LocalDate.of(2026, 1, 31));
		assertThat(clock.today()).isEqualTo(LocalDate.of(2026, 1, 31));
		assertThat(clock.now().getOffset()).isEqualTo(ZoneOffset.ofHours(7));
		assertThat(clock.now().toLocalDate()).isEqualTo(LocalDate.of(2026, 1, 31));
	}

	@Test
	void fixedClockNormalizesForeignOffsetsToTheBusinessZone() {
		OffsetDateTime utc = OffsetDateTime.of(2026, 9, 12, 3, 0, 0, 0, ZoneOffset.UTC);
		FixedClock clock = new FixedClock(utc);

		assertThat(clock.zone().getId()).isEqualTo("Asia/Jakarta");
		// same instant, expressed in +07:00
		assertThat(clock.now().getOffset()).isEqualTo(ZoneOffset.ofHours(7));
		assertThat(clock.instant()).isEqualTo(utc.toInstant());
		assertThat(clock.now().toLocalDate()).isEqualTo(LocalDate.of(2026, 9, 12));
	}
}