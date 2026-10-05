package com.serfira.contract.application;

import com.serfira.contract.api.CreditApplicationResponse;
import com.serfira.shared.concurrency.Sleeper;
import jakarta.persistence.OptimisticLockException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.UUID;

/**
 * Retries {@link ContractCreditCommandService#apply} across an optimistic-lock race (task T14, mirroring
 * {@code PaymentRetryingService}): a credit application mutates an {@code Installment} and a
 * {@code ContractCredit}, both {@code @Version}, so it can collide with the daily servicing job or a
 * concurrent payment on the same rows.
 *
 * <p><b>Deliberately not {@code @Transactional}.</b> This bean sits outside
 * {@link ContractCreditCommandService#apply}'s transaction boundary and calls it fresh on every attempt,
 * so each attempt opens its own transaction and sees the state the losing attempt left behind, never a
 * stale view. A failed attempt rolls back its application rows and status flips together.
 *
 * <p>Only an optimistic-lock failure is retryable here — unlike the payment path there is no idempotency
 * or accrual uniqueness constraint to classify — so a tiny local predicate is clearer than importing
 * {@code payment}'s classifier (which {@code contract} may not do anyway, 02_TECH_SPEC.md §1). A
 * hand-written loop with an injectable {@link Sleeper} is used instead of {@code spring-retry}: three
 * attempts with a fixed backoff does not justify a new dependency.
 */
@Service
public class ContractCreditRetryingService {

	private static final Logger LOGGER = LoggerFactory.getLogger(ContractCreditRetryingService.class);

	/** Up to 3 attempts total (first plus 2 retries); the two gaps pause 50 ms then 150 ms (as T5). */
	static final int MAX_ATTEMPTS = 3;
	static final long[] BACKOFF_MILLIS = {50L, 150L};

	private final ContractCreditCommandService commands;
	private final Sleeper sleeper;

	public ContractCreditRetryingService(ContractCreditCommandService commands, Sleeper sleeper) {
		this.commands = commands;
		this.sleeper = sleeper;
	}

	public CreditApplicationResponse apply(UUID contractId, BigDecimal requestedAmount) {
		RuntimeException lastFailure = null;
		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			try {
				return commands.apply(contractId, requestedAmount);
			} catch (RuntimeException failure) {
				if (!isOptimisticLockConflict(failure)) {
					throw failure;
				}
				lastFailure = failure;
				if (attempt < MAX_ATTEMPTS) {
					LOGGER.warn("Retrying credit apply after attempt {} ({})", attempt,
							failure.getClass().getSimpleName());
					sleeper.sleep(BACKOFF_MILLIS[attempt - 1]);
				}
			}
		}
		LOGGER.error("Credit apply failed after {} attempt(s) due to a repeated conflict", MAX_ATTEMPTS);
		throw new CreditApplyRetriesExhaustedException(MAX_ATTEMPTS, lastFailure);
	}

	private static boolean isOptimisticLockConflict(Throwable failure) {
		Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		for (Throwable cause = failure; cause != null && visited.add(cause); cause = cause.getCause()) {
			if (cause instanceof OptimisticLockingFailureException || cause instanceof OptimisticLockException) {
				return true;
			}
		}
		return false;
	}
}
