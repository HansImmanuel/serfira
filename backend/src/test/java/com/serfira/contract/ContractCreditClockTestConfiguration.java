package com.serfira.contract;

import com.serfira.shared.clock.Clock;
import com.serfira.shared.clock.FixedClock;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.LocalDate;

/**
 * Deterministic clock for {@link ContractCreditIT}. Pinned at the first installment due date so an
 * overpayment and a credit application behave identically regardless of the day the suite runs; tests
 * that need later installments due move this same clock explicitly.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ContractCreditClockTestConfiguration {

	/** First installment due date of the fixture contract (start 2026-01-31). */
	public static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 2, 28);

	@Bean
	@Primary
	Clock fixedClock() {
		return new FixedClock(BUSINESS_DATE);
	}
}
