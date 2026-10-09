package com.serfira.settlement.application;

import com.serfira.shared.error.ErrorCode;
import com.serfira.shared.error.SerfiraException;
import org.springframework.http.HttpStatus;

/**
 * 409 — a settlement-quote call retried an optimistic-lock conflict (its accrue-before-resolve billing /
 * penalty accrual races the daily servicing job on the same versioned installment rows) up to the
 * documented limit and every attempt still lost the race (E1, task T12, mirroring the credit-apply and
 * payment paths). The client refreshes and retries manually; this type exists only so exhausted retries
 * are distinguishable from an un-retried conflict in logs/tests.
 */
public final class SettlementQuoteRetriesExhaustedException extends SerfiraException {

	public SettlementQuoteRetriesExhaustedException(int attempts, Throwable lastFailure) {
		super(ErrorCode.CONCURRENT_MODIFICATION, HttpStatus.CONFLICT,
				"Settlement quote could not be priced after " + attempts
						+ " attempt(s) due to a concurrent job conflict; refresh and retry",
				lastFailure);
	}
}
