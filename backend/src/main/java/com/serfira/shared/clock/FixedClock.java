package com.serfira.shared.clock;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Objects;

/**
 * Deterministic {@link Clock} for tests and for time-sensitive scenarios where a fixed point in time is
 * required. Mutating the current instant makes schedule/penalty/settlement logic testable without sleeps.
 */
public final class FixedClock implements Clock {

	private final ZoneId zone;
	private OffsetDateTime current;

	public FixedClock(OffsetDateTime current) {
		this.current = Objects.requireNonNull(current, "current");
		this.zone = current.getOffset();
	}

	public FixedClock(LocalDate date) {
		this(date, SystemClock.SERFIRA_ZONE);
	}

	public FixedClock(LocalDate date, ZoneId zone) {
		this(date.atStartOfDay(zone).toOffsetDateTime());
	}

	/** Advances the fixed instant by the given duration. */
	public void advanceBy(Duration duration) {
		current = current.plus(duration);
	}

	/** Sets the instant to the given value. */
	public void set(OffsetDateTime value) {
		current = Objects.requireNonNull(value, "value");
	}

	/** Sets the instant to the given business date at midnight in the clock's zone. */
	public void setDate(LocalDate date) {
		current = date.atStartOfDay(zone).toOffsetDateTime();
	}

	@Override
	public ZoneId zone() {
		return zone;
	}

	@Override
	public OffsetDateTime now() {
		return current;
	}

	@Override
	public LocalDate today() {
		return current.toLocalDate();
	}

	@Override
	public Instant instant() {
		return current.toInstant();
	}
}