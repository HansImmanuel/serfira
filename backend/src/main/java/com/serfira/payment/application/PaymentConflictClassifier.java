package com.serfira.payment.application;

import jakarta.persistence.OptimisticLockException;
import org.springframework.dao.OptimisticLockingFailureException;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Retry classification for the payment write path (Addendum §5, `docs/tasks.md` T5): a job-versus-payment
 * race on optimistic locking or the {@code uk_penalty_accrual} unique constraint is retried; deterministic
 * validation and business-state errors are not.
 *
 * <p><b>Not every {@code 23505} is retryable</b> (ADR-017, review CR-04). The only uniqueness conflict a
 * retry can clear is {@code uk_penalty_accrual}: the losing side of a payment-versus-job race on the same
 * {@code (installment, accrual_date)} succeeds once it re-reads the committed accrual. A
 * {@code uq_payment_idempotency} hit is the opposite — the key already produced a POSTED payment, so
 * retrying only redoes billing and accrual and fails again. That violation is translated to an
 * expired-key rejection upstream ({@code PaymentApplicationService}) and must <b>not</b> be retried here,
 * so the classifier matches on the constraint name rather than the bare SQLSTATE.
 *
 * <p>Deliberately payment-owned rather than reusing
 * {@code com.serfira.penalty.application.DailyServicingConflictClassifier}: that type is package-private
 * to {@code penalty}, and {@code payment} must not add a cross-module import to reach it
 * (`10-architecture`). The two classifiers no longer share identical logic — the daily job has no
 * idempotency constraint to exclude — so promoting this to a {@code shared} abstraction is still not
 * justified (`35-coding-standards` §1/§19).
 */
final class PaymentConflictClassifier {

	private static final String UNIQUE_VIOLATION_SQL_STATE = "23505";

	/** The one uniqueness conflict a retry can clear: a payment-versus-job accrual race (V1). */
	private static final String RETRYABLE_CONSTRAINT = "uk_penalty_accrual";

	private PaymentConflictClassifier() {
	}

	static boolean isRetryable(Throwable failure) {
		Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		for (Throwable cause = failure; cause != null && visited.add(cause); cause = cause.getCause()) {
			if (cause instanceof OptimisticLockingFailureException || cause instanceof OptimisticLockException) {
				return true;
			}
			if (cause instanceof SQLException sqlException
					&& UNIQUE_VIOLATION_SQL_STATE.equals(sqlException.getSQLState())
					&& namesRetryableConstraint(sqlException)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * True only when the violation is the retryable accrual race. Any other {@code 23505} (notably
	 * {@code uq_payment_idempotency}) is a spent-key conflict that a retry cannot clear.
	 */
	private static boolean namesRetryableConstraint(SQLException sqlException) {
		String message = sqlException.getMessage();
		return message != null && message.contains(RETRYABLE_CONSTRAINT);
	}
}
