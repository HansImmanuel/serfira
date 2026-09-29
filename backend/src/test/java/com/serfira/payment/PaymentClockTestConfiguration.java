package com.serfira.payment;

import com.serfira.shared.clock.Clock;
import com.serfira.shared.clock.FixedClock;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.LocalDate;

/**
 * Deterministic clock shared by payment integration suites.
 *
 * <p>Payment behaviour is time dependent in three ways that must not depend on the day the suite runs:
 * {@code paid_at} comes from the clock (TS §2.0), the allocation window is
 * {@code due_date <= business date} (ADR-009), and T4 recognizes due penalties before taking the payment
 * snapshot. Pinning the business date at the first due date, 2026-02-28, makes only period 1 due and keeps
 * the normal payment fixtures outside the penalty grace boundary. Tests for late behavior move this same
 * clock explicitly.
 *
 * <p>Same recipe as {@code DocumentNumberGeneratorIT}: named differently from
 * {@code ClockConfiguration#clock} so both definitions coexist, and {@code @Primary} so the fixed bean
 * is the one injected everywhere a {@link Clock} is required.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PaymentClockTestConfiguration {

	/** First installment due date used as the normal payment-suite business date. */
	public static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 2, 28);

	@Bean
	@Primary
	Clock fixedClock() {
		return new FixedClock(BUSINESS_DATE);
	}
}
