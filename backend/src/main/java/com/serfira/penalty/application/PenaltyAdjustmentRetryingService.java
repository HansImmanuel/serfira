package com.serfira.penalty.application;

import com.serfira.penalty.domain.PenaltyAdjustmentType;
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
 * Retries {@link PenaltyAdjustmentService#adjust} across an optimistic-lock race (task T15, mirroring
 * {@code ContractCreditRetryingService} / {@code PaymentRetryingService}): a waiver recomputes the affected
 * installment's resolution status, writing the {@code @Version}ed {@code installment} row, so two concurrent
 * waivers on the same installment collide on {@code @Version} and exactly one may commit.
 *
 * <p><b>Deliberately not {@code @Transactional}.</b> This bean sits OUTSIDE
 * {@link PenaltyAdjustmentService#adjust}'s transaction boundary and calls it fresh on every attempt, so
 * each attempt opens its own transaction and reads the state the losing attempt committed, never a stale
 * view. A failed attempt rolls back its adjustment row, its journal and the installment write together.
 *
 * <p>Only an optimistic-lock failure is retryable here — there is no idempotency or accrual-uniqueness
 * constraint on this path — so a tiny local predicate is clearer than importing {@code payment}'s
 * classifier (which {@code penalty} may not depend on anyway, 02_TECH_SPEC.md §1). A hand-written loop with
 * an injectable {@link Sleeper} is used instead of {@code spring-retry}: three attempts with a fixed backoff
 * does not justify a new dependency.
 */
@Service
public class PenaltyAdjustmentRetryingService {

	private static final Logger LOGGER = LoggerFactory.getLogger(PenaltyAdjustmentRetryingService.class);

	/** Up to 3 attempts total (first plus 2 retries); the two gaps pause 50 ms then 150 ms (as T5/T14). */
	static final int MAX_ATTEMPTS = 3;
	static final long[] BACKOFF_MILLIS = {50L, 150L};

	private final PenaltyAdjustmentService adjustments;
	private final Sleeper sleeper;

	public PenaltyAdjustmentRetryingService(PenaltyAdjustmentService adjustments, Sleeper sleeper) {
		this.adjustments = adjustments;
		this.sleeper = sleeper;
	}

	public PenaltyAdjustmentResult adjust(UUID contractId, UUID installmentId, PenaltyAdjustmentType type,
			BigDecimal amount, String reason) {
		RuntimeException lastFailure = null;
		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			try {
				return adjustments.adjust(contractId, installmentId, type, amount, reason);
			} catch (RuntimeException failure) {
				if (!isOptimisticLockConflict(failure)) {
					throw failure;
				}
				lastFailure = failure;
				if (attempt < MAX_ATTEMPTS) {
					LOGGER.warn("Retrying penalty adjustment after attempt {} ({})", attempt,
							failure.getClass().getSimpleName());
					sleeper.sleep(BACKOFF_MILLIS[attempt - 1]);
				}
			}
		}
		LOGGER.error("Penalty adjustment failed after {} attempt(s) due to a repeated conflict", MAX_ATTEMPTS);
		throw new PenaltyAdjustmentRetriesExhaustedException(MAX_ATTEMPTS, lastFailure);
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
