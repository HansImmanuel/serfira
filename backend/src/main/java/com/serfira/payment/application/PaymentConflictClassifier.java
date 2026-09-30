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
 * <p>Deliberately payment-owned rather than reusing
 * {@code com.serfira.penalty.application.DailyServicingConflictClassifier}: that type is package-private
 * to {@code penalty}, and {@code payment} must not add a cross-module import to reach it
 * (`10-architecture`). The two classifiers share the same SQLSTATE/optimistic-lock rule because they both
 * retry the same underlying race, not because of any shared contract between the modules — promoting this
 * to a {@code shared} abstraction is not justified by two call sites for ~15 lines of logic
 * (`35-coding-standards` §1/§19).
 */
final class PaymentConflictClassifier {

	private static final String UNIQUE_VIOLATION_SQL_STATE = "23505";

	private PaymentConflictClassifier() {
	}

	static boolean isRetryable(Throwable failure) {
		Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		for (Throwable cause = failure; cause != null && visited.add(cause); cause = cause.getCause()) {
			if (cause instanceof OptimisticLockingFailureException || cause instanceof OptimisticLockException) {
				return true;
			}
			if (cause instanceof SQLException sqlException
					&& UNIQUE_VIOLATION_SQL_STATE.equals(sqlException.getSQLState())) {
				return true;
			}
		}
		return false;
	}
}
