package com.serfira.shared.clock;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * Production {@link Clock} backed by the system clock and the Serfira business timezone (Asia/Jakarta).
 */
public final class SystemClock implements Clock {

	public static final String SERFIRA_ZONE_ID = "Asia/Jakarta";
	public static final ZoneId SERFIRA_ZONE = ZoneId.of(SERFIRA_ZONE_ID);

	@Override
	public ZoneId zone() {
		return SERFIRA_ZONE;
	}

	@Override
	public OffsetDateTime now() {
		return OffsetDateTime.now(SERFIRA_ZONE);
	}

	@Override
	public LocalDate today() {
		return LocalDate.now(SERFIRA_ZONE);
	}

	@Override
	public Instant instant() {
		return Instant.now();
	}
}