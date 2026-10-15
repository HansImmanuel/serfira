package com.serfira.contract.application;

import com.serfira.contract.api.WriteOffContractRequest;
import com.serfira.contract.api.WriteOffResponse;
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
 * Retries {@link ContractWriteOffCommandService#writeOff} across an optimistic-lock race (F5, task T19,
 * mirroring {@code SettlementExecutionRetryingService}): the write-off's accrue-before-resolve step and
 * its installment write-offs mutate {@code @Version} installment and contract rows, so it can collide
 * with the daily servicing job on the same contract.
 *
 * <p><b>Deliberately not {@code @Transactional}.</b> It sits outside
 * {@link ContractWriteOffCommandService#writeOff}'s transaction boundary and calls it fresh on every
 * attempt, so each attempt opens its own transaction and takes the idempotency claim again
 * ({@code IdempotencyService} is {@code Propagation.MANDATORY} inside that transaction). A failed attempt
 * rolls back its claim with everything else — it never consumes the key — and the next attempt observes
 * the state the losing attempt left behind.
 *
 * <p>Two failures are retryable, exactly as the settlement path classifies them: an optimistic-lock
 * failure, and the {@code uk_penalty_accrual} unique violation ({@code 23505}) of a write-off-versus-job
 * accrual race. Any other {@code 23505} is not a race a retry can clear, so the predicate matches on the
 * constraint name, not the bare SQLSTATE.
 */
@Service
public class ContractWriteOffRetryingService {

	private static final Logger LOGGER = LoggerFactory.getLogger(ContractWriteOffRetryingService.class);

	/** Up to 3 attempts total (first plus 2 retries); the two gaps pause 50 ms then 150 ms (as T5/T13/T14). */
	static final int MAX_ATTEMPTS = 3;
	static final long[] BACKOFF_MILLIS = {50L, 150L};

	private static final String UNIQUE_VIOLATION_SQL_STATE = "23505";

	/** The one uniqueness conflict a retry can clear: a write-off-versus-job accrual race (V1). */
	private static final String RETRYABLE_CONSTRAINT = "uk_penalty_accrual";

	private final ContractWriteOffCommandService commands;
	private final Sleeper sleeper;

	public ContractWriteOffRetryingService(ContractWriteOffCommandService commands, Sleeper sleeper) {
		this.commands = commands;
		this.sleeper = sleeper;
	}

	public WriteOffResponse writeOff(UUID contractId, String idempotencyKey, WriteOffContractRequest request) {
		RuntimeException lastFailure = null;
		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			try {
				return commands.writeOff(contractId, idempotencyKey, request);
			} catch (RuntimeException failure) {
				if (!isRetryable(failure)) {
					throw failure;
				}
				lastFailure = failure;
				if (attempt < MAX_ATTEMPTS) {
					LOGGER.warn("Retrying write-off after attempt {} ({})", attempt,
							failure.getClass().getSimpleName());
					sleeper.sleep(BACKOFF_MILLIS[attempt - 1]);
				}
			}
		}
		LOGGER.error("Write-off failed after {} attempt(s) due to a repeated conflict", MAX_ATTEMPTS);
		throw new WriteOffRetriesExhaustedException(MAX_ATTEMPTS, lastFailure);
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

	private static boolean namesRetryableConstraint(SQLException sqlException) {
		String message = sqlException.getMessage();
		return message != null && message.contains(RETRYABLE_CONSTRAINT);
	}
}
