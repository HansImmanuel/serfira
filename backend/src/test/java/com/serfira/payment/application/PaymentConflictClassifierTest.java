package com.serfira.payment.application;

import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Retry classification for the payment write path (T24, ADR-017 D4): the only retryable uniqueness
 * conflict is the {@code uk_penalty_accrual} payment-versus-job race. A {@code uq_payment_idempotency}
 * {@code 23505} is a spent-key conflict a retry cannot clear and must <b>not</b> be retried — otherwise
 * the payment keeps redoing billing and accrual before failing again (review CR-04).
 */
class PaymentConflictClassifierTest {

	private static final String SQL_STATE_UNIQUE_VIOLATION = "23505";

	@Test
	void optimisticLockFailureIsRetryable() {
		assertThat(PaymentConflictClassifier.isRetryable(new ObjectOptimisticLockingFailureException("payment", 1L)))
				.isTrue();
	}

	@Test
	void jpaOptimisticLockExceptionIsRetryable() {
		assertThat(PaymentConflictClassifier.isRetryable(new OptimisticLockException("stale version")))
				.isTrue();
	}

	@Test
	void aPenaltyAccrualRaceIsRetryable() {
		Throwable accrualRace = uniqueViolation(
				"ERROR: duplicate key value violates unique constraint \"uk_penalty_accrual\"");

		assertThat(PaymentConflictClassifier.isRetryable(accrualRace)).isTrue();
	}

	@Test
	void aPaymentIdempotencyViolationIsNotRetryable() {
		Throwable spentKey = uniqueViolation(
				"ERROR: duplicate key value violates unique constraint \"uq_payment_idempotency\"");

		assertThat(PaymentConflictClassifier.isRetryable(spentKey)).isFalse();
	}

	@Test
	void aUniqueViolationNamingNoKnownConstraintIsNotRetryable() {
		Throwable other = uniqueViolation(
				"ERROR: duplicate key value violates unique constraint \"uq_contract_live_asset\"");

		assertThat(PaymentConflictClassifier.isRetryable(other)).isFalse();
	}

	@Test
	void anUnrelatedFailureIsNotRetryable() {
		assertThat(PaymentConflictClassifier.isRetryable(new IllegalStateException("bad state")))
				.isFalse();
	}

	@Test
	void aNullFailureIsNotRetryable() {
		assertThat(PaymentConflictClassifier.isRetryable(null)).isFalse();
	}

	/**
	 * The shape Spring Data presents for a PostgreSQL unique-constraint hit: a
	 * {@link DataIntegrityViolationException} wrapping a {@link SQLException} whose SQLSTATE is
	 * {@code 23505} and whose message names the constraint, mirroring how the classifier walks the
	 * cause chain in production.
	 */
	private static Throwable uniqueViolation(String message) {
		SQLException sql = new SQLException(message, SQL_STATE_UNIQUE_VIOLATION);
		return new DataIntegrityViolationException("could not execute statement", sql);
	}
}
