package com.serfira.penalty.application;

import com.serfira.shared.error.ErrorCode;
import com.serfira.shared.error.SerfiraException;
import org.springframework.http.HttpStatus;

/**
 * 409 — a penalty waive/reduce retried an optimistic-lock conflict (versus a concurrent waiver on the same
 * installment, which collides on {@code installment.@Version} once the waiver recomputes the installment's
 * status) up to the documented limit and every attempt still lost the race (task T15, mirroring the payment
 * and credit paths). The client refreshes and retries manually; this type exists only so exhausted retries
 * are distinguishable from an un-retried conflict in logs/tests.
 */
public final class PenaltyAdjustmentRetriesExhaustedException extends SerfiraException {

	public PenaltyAdjustmentRetriesExhaustedException(int attempts, Throwable lastFailure) {
		super(ErrorCode.CONCURRENT_MODIFICATION, HttpStatus.CONFLICT,
				"Penalty adjustment could not be recorded after " + attempts
						+ " attempt(s) due to a concurrent waiver conflict; refresh and retry",
				lastFailure);
	}
}
