package com.serfira.contract.application;

import com.serfira.shared.error.ErrorCode;
import com.serfira.shared.error.SerfiraException;
import org.springframework.http.HttpStatus;

/**
 * 409 — a write-off call retried an optimistic-lock conflict (versus the daily servicing job on the same
 * contract's installments, surfaced by the accrue-before-resolve step) up to the documented limit and
 * every attempt still lost the race (F5, task T19, mirroring the settlement/credit/payment paths). The
 * client refreshes and retries manually; this type exists only so exhausted retries are distinguishable
 * from an un-retried conflict in logs/tests.
 */
public final class WriteOffRetriesExhaustedException extends SerfiraException {

	public WriteOffRetriesExhaustedException(int attempts, Throwable lastFailure) {
		super(ErrorCode.CONCURRENT_MODIFICATION, HttpStatus.CONFLICT,
				"Contract could not be written off after " + attempts
						+ " attempt(s) due to a concurrent job conflict; refresh and retry",
				lastFailure);
	}
}
