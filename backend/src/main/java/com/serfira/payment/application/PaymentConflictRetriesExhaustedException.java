package com.serfira.payment.application;

import com.serfira.shared.error.ErrorCode;
import com.serfira.shared.error.SerfiraException;
import org.springframework.http.HttpStatus;

/**
 * 409 — the payment write path retried a job-versus-payment conflict (optimistic lock or
 * {@code uk_penalty_accrual} uniqueness) up to the documented limit and every attempt still lost the
 * race (Addendum §5, `docs/tasks.md` T5). The client is expected to refresh and retry manually, exactly
 * like the generic optimistic-lock mapping in {@code GlobalExceptionHandler} — this type exists only so
 * exhausted retries are distinguishable from an un-retried conflict in logs and tests.
 */
public final class PaymentConflictRetriesExhaustedException extends SerfiraException {

	public PaymentConflictRetriesExhaustedException(int attempts, Throwable lastFailure) {
		super(ErrorCode.CONCURRENT_MODIFICATION, HttpStatus.CONFLICT,
				"Payment could not be posted after " + attempts
						+ " attempt(s) due to a concurrent job or payment conflict; refresh and retry",
				lastFailure);
	}
}
