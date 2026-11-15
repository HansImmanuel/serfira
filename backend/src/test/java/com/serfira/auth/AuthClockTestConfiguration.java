package com.serfira.auth;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import com.serfira.shared.clock.Clock;
import com.serfira.shared.clock.FixedClock;

/**
 * Deterministic clock for {@link AuthLoginIT} (T21a).
 *
 * <p>Pinned once when the suite loads, so the issued-at/expiry window and {@code last_login_at}
 * are exact within a run. It is captured relative to the machine clock (truncated to seconds)
 * rather than hard-coded: the decoder validates {@code exp} against the real clock, so a fixed
 * instant in the past would make every freshly minted access token already expired. Named
 * differently from {@code ClockConfiguration#clock} so both definitions coexist, and
 * {@code @Primary} so the fixed bean is the one injected everywhere a {@link Clock} is required.
 */
@TestConfiguration(proxyBeanMethods = false)
public class AuthClockTestConfiguration {

	/** Business instant for the login suite, captured when the test class loads. */
	public static final OffsetDateTime FIXED_NOW =
			OffsetDateTime.now(ZoneOffset.ofHours(7)).truncatedTo(ChronoUnit.SECONDS);

	@Bean
	@Primary
	Clock authFixedClock() {
		return new FixedClock(FIXED_NOW);
	}
}
