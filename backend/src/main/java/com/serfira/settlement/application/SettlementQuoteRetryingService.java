package com.serfira.settlement.application;

import com.serfira.settlement.api.SettlementQuoteResponse;
import com.serfira.shared.concurrency.Sleeper;
import jakarta.persistence.OptimisticLockException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.UUID;

/**
 * Retries {@link SettlementQuoteCommandService#quote} across an optimistic-lock race (E1, task T12,
 * mirroring {@code ContractCreditRetryingService} / {@code PaymentRetryingService}): the quote's
 * accrue-before-resolve step bills interest and accrues penalty, both of which mutate {@code @Version}
 * installment rows, so it can collide with the daily servicing job on the same contract.
 *
 * <p><b>Deliberately not {@code @Transactional}.</b> It sits outside
 * {@link SettlementQuoteCommandService#quote}'s transaction boundary and calls it fresh on every attempt,
 * so each attempt opens its own transaction and sees the state the losing attempt left behind. A failed
 * attempt rolls back its billing / accrual together.
 *
 * <p>Two failures are retryable, exactly as the payment path classifies them
 * ({@code PaymentConflictClassifier}): an optimistic-lock failure, and the {@code uk_penalty_accrual}
 * unique violation ({@code 23505}) the accrue-before-resolve step can hit when it races the daily job on
 * the same {@code (installment, accrual_date)} — the losing side succeeds once it re-reads the committed
 * accrual. A quote has no idempotency key (T13's execution does), so unlike payment there is no
 * {@code uq_*_idempotency} 23505 to exclude; still, the predicate matches on the constraint name rather
 * than the bare SQLSTATE so an unrelated future uniqueness conflict is not retried blindly (ADR-017,
 * PR #9 review finding 3). A hand-written loop with an injectable {@link Sleeper} matches the
 * three-attempt / 50-150 ms backoff used elsewhere without pulling in {@code spring-retry}.
 */
@Service
public class SettlementQuoteRetryingService {

	private static final Logger LOGGER = LoggerFactory.getLogger(SettlementQuoteRetryingService.class);

	/** Up to 3 attempts total (first plus 2 retries); the two gaps pause 50 ms then 150 ms (as T5/T14). */
	static final int MAX_ATTEMPTS = 3;
	static final long[] BACKOFF_MILLIS = {50L, 150L};

	private static final String UNIQUE_VIOLATION_SQL_STATE = "23505";

	/** The one uniqueness conflict a retry can clear: a quote-versus-job accrual race (V1). */
	private static final String RETRYABLE_CONSTRAINT = "uk_penalty_accrual";

	private final SettlementQuoteCommandService commands;
	private final Sleeper sleeper;

	public SettlementQuoteRetryingService(SettlementQuoteCommandService commands, Sleeper sleeper) {
		this.commands = commands;
		this.sleeper = sleeper;
	}

	public SettlementQuoteResponse quote(UUID contractId) {
		RuntimeException lastFailure = null;
		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			try {
				return commands.quote(contractId);
			} catch (RuntimeException failure) {
				if (!isRetryable(failure)) {
					throw failure;
				}
				lastFailure = failure;
				if (attempt < MAX_ATTEMPTS) {
					LOGGER.warn("Retrying settlement quote after attempt {} ({})", attempt,
							failure.getClass().getSimpleName());
					sleeper.sleep(BACKOFF_MILLIS[attempt - 1]);
				}
			}
		}
		LOGGER.error("Settlement quote failed after {} attempt(s) due to a repeated conflict", MAX_ATTEMPTS);
		throw new SettlementQuoteRetriesExhaustedException(MAX_ATTEMPTS, lastFailure);
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
	 * True only when the violation is the retryable accrual race. Any other {@code 23505} is a conflict a
	 * retry cannot clear, so it is rethrown rather than re-attempted.
	 */
	private static boolean namesRetryableConstraint(SQLException sqlException) {
		String message = sqlException.getMessage();
		return message != null && message.contains(RETRYABLE_CONSTRAINT);
	}
}
