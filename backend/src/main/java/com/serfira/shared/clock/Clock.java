package com.serfira.shared.clock;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * Application time boundary.
 *
 * <p>Business logic must never call {@code LocalDate.now()} / {@code Instant.now()} directly; it reads time
 * through this interface so that dates are deterministic under test (see {@link FixedClock}) and always
 * anchored to the Serfira business timezone (Asia/Jakarta, TECH SPEC §2.0).
 */
public interface Clock {

	/** Business timezone of the application (Asia/Jakarta). */
	ZoneId zone();

	/** Current instant expressed in the business timezone. */
	OffsetDateTime now();

	/** Current business date in the business timezone. */
	LocalDate today();

	/** Current UTC instant, for machine-readable timestamps. */
	Instant instant();
}