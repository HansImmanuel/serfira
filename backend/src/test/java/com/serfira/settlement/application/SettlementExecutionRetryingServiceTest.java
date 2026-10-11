package com.serfira.settlement.application;

import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Retry classification for the settlement-execution path (T13, ADR-018), mirroring the quote and payment
 * classifiers. The execution's accrue-before-resolve step and its installment settlement race the daily
 * job, so an optimistic-lock conflict and the {@code uk_penalty_accrual} unique violation are retryable.
 * The settlement's own uniqueness conflicts — {@code uq_settlement_idempotency} (a spent key) and
 * {@code uk_settlement_quote_id} (a quote already executed) — are not races a retry can clear and must not
 * be retried.
 */
class SettlementExecutionRetryingServiceTest {

	private static final String SQL_STATE_UNIQUE_VIOLATION = "23505";

	@Test
	void optimisticLockFailureIsRetryable() {
		assertThat(SettlementExecutionRetryingService.isRetryable(
				new ObjectOptimisticLockingFailureException("installment", 1L))).isTrue();
	}

	@Test
	void jpaOptimisticLockExceptionIsRetryable() {
		assertThat(SettlementExecutionRetryingService.isRetryable(new OptimisticLockException("stale version")))
				.isTrue();
	}

	@Test
	void aPenaltyAccrualRaceIsRetryable() {
		Throwable accrualRace = uniqueViolation(
				"ERROR: duplicate key value violates unique constraint \"uk_penalty_accrual\"");

		assertThat(SettlementExecutionRetryingService.isRetryable(accrualRace)).isTrue();
	}

	@Test
	void aSpentIdempotencyKeyIsNotRetryable() {
		Throwable spentKey = uniqueViolation(
				"ERROR: duplicate key value violates unique constraint \"uq_settlement_idempotency\"");

		assertThat(SettlementExecutionRetryingService.isRetryable(spentKey)).isFalse();
	}

	@Test
	void anAlreadyExecutedQuoteIsNotRetryable() {
		Throwable alreadyExecuted = uniqueViolation(
				"ERROR: duplicate key value violates unique constraint \"uk_settlement_quote_id\"");

		assertThat(SettlementExecutionRetryingService.isRetryable(alreadyExecuted)).isFalse();
	}

	@Test
	void anUnrelatedFailureIsNotRetryable() {
		assertThat(SettlementExecutionRetryingService.isRetryable(new IllegalStateException("bad state")))
				.isFalse();
	}

	@Test
	void aNullFailureIsNotRetryable() {
		assertThat(SettlementExecutionRetryingService.isRetryable(null)).isFalse();
	}

	private static Throwable uniqueViolation(String message) {
		SQLException sql = new SQLException(message, SQL_STATE_UNIQUE_VIOLATION);
		return new DataIntegrityViolationException("could not execute statement", sql);
	}
}
