package com.serfira.settlement.application;

import com.serfira.shared.error.ErrorCode;
import com.serfira.shared.error.SerfiraException;
import org.springframework.http.HttpStatus;

/**
 * 409 — a settlement execution retried an optimistic-lock conflict (its accrue-before-resolve billing /
 * penalty accrual, and the installment settlement, race the daily servicing job on the same versioned
 * installment rows) up to the documented limit and every attempt still lost the race (E2, task T13,
 * mirroring {@code SettlementQuoteRetriesExhaustedException} and the payment path). The client refreshes
 * and retries manually; this type exists only so exhausted retries are distinguishable from an un-retried
 * conflict in logs/tests.
 */
public final class SettlementExecutionRetriesExhaustedException extends SerfiraException {

	public SettlementExecutionRetriesExhaustedException(int attempts, Throwable lastFailure) {
		super(ErrorCode.CONCURRENT_MODIFICATION, HttpStatus.CONFLICT,
				"Settlement could not be executed after " + attempts
						+ " attempt(s) due to a concurrent job conflict; refresh and retry",
				lastFailure);
	}
}
