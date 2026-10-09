package com.serfira.settlement;

import com.serfira.shared.clock.Clock;
import com.serfira.shared.clock.FixedClock;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.LocalDate;

/**
 * Deterministic clock for {@link SettlementQuoteIT}. Pinned before the first due date so the fixture is
 * stable regardless of the day the suite runs; tests move this same clock explicitly to make installments
 * due and to exercise the accrue-before-resolve step.
 */
@TestConfiguration(proxyBeanMethods = false)
public class SettlementQuoteClockTestConfiguration {

	/** A day before the Demo-B contract's first activity (start 2026-01-15, first due 2026-02-15). */
	public static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 1, 15);

	@Bean
	@Primary
	Clock fixedClock() {
		return new FixedClock(BUSINESS_DATE);
	}
}
