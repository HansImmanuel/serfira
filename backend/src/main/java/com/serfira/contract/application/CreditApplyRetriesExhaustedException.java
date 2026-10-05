package com.serfira.contract.application;

import com.serfira.shared.error.ErrorCode;
import com.serfira.shared.error.SerfiraException;
import org.springframework.http.HttpStatus;

/**
 * 409 — a credit-apply call retried an optimistic-lock conflict (versus the daily servicing job or a
 * concurrent payment on the same installment/credit) up to the documented limit and every attempt still
 * lost the race (task T14, mirroring the payment path). The client refreshes and retries manually; this
 * type exists only so exhausted retries are distinguishable from an un-retried conflict in logs/tests.
 */
public final class CreditApplyRetriesExhaustedException extends SerfiraException {

	public CreditApplyRetriesExhaustedException(int attempts, Throwable lastFailure) {
		super(ErrorCode.CONCURRENT_MODIFICATION, HttpStatus.CONFLICT,
				"Credit could not be applied after " + attempts
						+ " attempt(s) due to a concurrent job or payment conflict; refresh and retry",
				lastFailure);
	}
}
