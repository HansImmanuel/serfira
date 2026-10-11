package com.serfira.settlement.application;

import com.serfira.settlement.api.SettlementExecutionRequest;
import com.serfira.settlement.api.SettlementExecutionResponse;
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

/**
 * Retries {@link SettlementExecutionCommandService#execute} across an optimistic-lock race (E2, task T13,
 * mirroring {@code SettlementQuoteRetryingService} / {@code PaymentRetryingService}): the execution's
 * accrue-before-resolve step and its installment settlement both mutate {@code @Version} installment rows,
 * so it can collide with the daily servicing job on the same contract.
 *
 * <p><b>Deliberately not {@code @Transactional}.</b> It sits outside
 * {@link SettlementExecutionCommandService#execute}'s transaction boundary and calls it fresh on every
 * attempt, so each attempt opens its own transaction and takes the idempotency claim again
 * ({@code IdempotencyService} is {@code Propagation.MANDATORY} inside that transaction). A failed attempt
 * rolls back its claim with everything else — it never consumes the key — and the next attempt observes the
 * state the losing attempt left behind.
 *
 * <p>Two failures are retryable, exactly as the payment and quote paths classify them: an optimistic-lock
 * failure, and the {@code uk_penalty_accrual} unique violation ({@code 23505}) of an accrue-versus-job
 * race. The settlement's own uniqueness conflicts — {@code uq_settlement_idempotency} (a spent key) and
 * {@code uk_settlement_quote_id} (a quote already executed) — are <b>not</b> races a retry can clear, so
 * the predicate matches on the constraint name rather than the bare SQLSTATE and excludes them.
 */
@Service
public class SettlementExecutionRetryingService {

	private static final Logger LOGGER = LoggerFactory.getLogger(SettlementExecutionRetryingService.class);

	/** Up to 3 attempts total (first plus 2 retries); the two gaps pause 50 ms then 150 ms (as T5/T12/T14). */
	static final int MAX_ATTEMPTS = 3;
	static final long[] BACKOFF_MILLIS = {50L, 150L};

	private static final String UNIQUE_VIOLATION_SQL_STATE = "23505";

	/** The one uniqueness conflict a retry can clear: a settlement-versus-job accrual race (V1). */
	private static final String RETRYABLE_CONSTRAINT = "uk_penalty_accrual";

	private final SettlementExecutionCommandService commands;
	private final Sleeper sleeper;

	public SettlementExecutionRetryingService(SettlementExecutionCommandService commands, Sleeper sleeper) {
		this.commands = commands;
		this.sleeper = sleeper;
	}

	public SettlementExecutionResponse execute(String idempotencyKey, SettlementExecutionRequest request) {
		RuntimeException lastFailure = null;
		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			try {
				return commands.execute(idempotencyKey, request);
			} catch (RuntimeException failure) {
				if (!isRetryable(failure)) {
					throw failure;
				}
				lastFailure = failure;
				if (attempt < MAX_ATTEMPTS) {
					LOGGER.warn("Retrying settlement execution after attempt {} ({})", attempt,
							failure.getClass().getSimpleName());
					sleeper.sleep(BACKOFF_MILLIS[attempt - 1]);
				}
			}
		}
		LOGGER.error("Settlement execution failed after {} attempt(s) due to a repeated conflict", MAX_ATTEMPTS);
		throw new SettlementExecutionRetriesExhaustedException(MAX_ATTEMPTS, lastFailure);
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
	 * {@code uq_settlement_idempotency} or {@code uk_settlement_quote_id}) is a conflict a retry cannot
	 * clear, so it is rethrown rather than re-attempted.
	 */
	private static boolean namesRetryableConstraint(SQLException sqlException) {
		String message = sqlException.getMessage();
		return message != null && message.contains(RETRYABLE_CONSTRAINT);
	}
}
