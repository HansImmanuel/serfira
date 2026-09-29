package com.serfira.penalty.application;

import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DailyServicingConflictClassifierTest {

	@Test
	void retriesSpringAndJpaOptimisticLockFailures() {
		assertThat(DailyServicingConflictClassifier.isRetryable(
				new ObjectOptimisticLockingFailureException(Object.class, UUID.randomUUID()))).isTrue();
		assertThat(DailyServicingConflictClassifier.isRetryable(new OptimisticLockException("concurrent"))).isTrue();
	}

	@Test
	void retriesOnlyUniqueIntegrityViolations() {
		RuntimeException unique = new DataIntegrityViolationException("duplicate",
				new SQLException("duplicate key", "23505"));
		RuntimeException check = new DataIntegrityViolationException("check",
				new SQLException("check violation", "23514"));

		assertThat(DailyServicingConflictClassifier.isRetryable(unique)).isTrue();
		assertThat(DailyServicingConflictClassifier.isRetryable(check)).isFalse();
		assertThat(DailyServicingConflictClassifier.isRetryable(new IllegalStateException("deterministic"))).isFalse();
	}
}
