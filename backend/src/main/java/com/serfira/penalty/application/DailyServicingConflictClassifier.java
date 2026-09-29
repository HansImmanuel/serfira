package com.serfira.penalty.application;

import jakarta.persistence.OptimisticLockException;
import org.springframework.dao.OptimisticLockingFailureException;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Retry classification for job-vs-payment conflicts; deterministic integrity defects are not retried. */
final class DailyServicingConflictClassifier {

	private static final String UNIQUE_VIOLATION_SQL_STATE = "23505";

	private DailyServicingConflictClassifier() {
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
