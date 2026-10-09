package com.serfira.settlement.application;

import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Retry classification for the settlement-quote path (T12, ADR-018; PR #9 review finding 3). The quote's
 * accrue-before-resolve step bills interest and accrues penalty, so it races the daily job exactly like the
 * payment path. Two failures are retryable: an optimistic-lock conflict, and the {@code uk_penalty_accrual}
 * unique violation of a quote-versus-job accrual race (the losing side succeeds once it re-reads the
 * committed accrual). Unlike payment there is no idempotency constraint to exclude, but any other
 * {@code 23505} is still a conflict a retry cannot clear and must not be retried.
 */
class SettlementQuoteRetryingServiceTest {

	private static final String SQL_STATE_UNIQUE_VIOLATION = "23505";

	@Test
	void optimisticLockFailureIsRetryable() {
		assertThat(SettlementQuoteRetryingService.isRetryable(
				new ObjectOptimisticLockingFailureException("installment", 1L))).isTrue();
	}

	@Test
	void jpaOptimisticLockExceptionIsRetryable() {
		assertThat(SettlementQuoteRetryingService.isRetryable(new OptimisticLockException("stale version")))
				.isTrue();
	}

	@Test
	void aPenaltyAccrualRaceIsRetryable() {
		Throwable accrualRace = uniqueViolation(
				"ERROR: duplicate key value violates unique constraint \"uk_penalty_accrual\"");

		assertThat(SettlementQuoteRetryingService.isRetryable(accrualRace)).isTrue();
	}

	@Test
	void aUniqueViolationNamingNoKnownConstraintIsNotRetryable() {
		Throwable other = uniqueViolation(
				"ERROR: duplicate key value violates unique constraint \"uk_settlement_quote_no\"");

		assertThat(SettlementQuoteRetryingService.isRetryable(other)).isFalse();
	}

	@Test
	void anUnrelatedFailureIsNotRetryable() {
		assertThat(SettlementQuoteRetryingService.isRetryable(new IllegalStateException("bad state")))
				.isFalse();
	}

	@Test
	void aNullFailureIsNotRetryable() {
		assertThat(SettlementQuoteRetryingService.isRetryable(null)).isFalse();
	}

	/**
	 * The shape Spring Data presents for a PostgreSQL unique-constraint hit: a
	 * {@link DataIntegrityViolationException} wrapping a {@link SQLException} whose SQLSTATE is
	 * {@code 23505} and whose message names the constraint, mirroring how the predicate walks the cause
	 * chain in production.
	 */
	private static Throwable uniqueViolation(String message) {
		SQLException sql = new SQLException(message, SQL_STATE_UNIQUE_VIOLATION);
		return new DataIntegrityViolationException("could not execute statement", sql);
	}
}
