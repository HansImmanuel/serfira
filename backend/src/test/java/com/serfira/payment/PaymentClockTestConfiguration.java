package com.serfira.payment;

import com.serfira.shared.clock.Clock;
import com.serfira.shared.clock.FixedClock;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.LocalDate;

/**
 * Deterministic application clock for the payment integration tests.
 *
 * <p>Payment behaviour is time dependent in two ways that must not depend on the day the suite runs:
 * {@code paid_at} comes from the clock (TS §2.0) and the allocation window is
 * {@code due_date <= business date} (ADR-009). Pinning the business date at 2026-09-21 against a
 * schedule that starts 2026-01-31 makes periods 1–7 due and period 8 (2026-08-31 → 2026-09-30) the
 * first future installment, today and in five years.
 *
 * <p>Same recipe as {@code DocumentNumberGeneratorIT}: named differently from
 * {@code ClockConfiguration#clock} so both definitions coexist, and {@code @Primary} so the fixed bean
 * is the one injected everywhere a {@link Clock} is required.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PaymentClockTestConfiguration {

	/** Business date the payment suites run at. */
	public static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 9, 21);

	@Bean
	@Primary
	Clock fixedClock() {
		return new FixedClock(BUSINESS_DATE);
	}
}
