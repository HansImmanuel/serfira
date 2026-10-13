package com.serfira.payment.application;

import com.serfira.payment.api.PaymentResponse;
import com.serfira.payment.api.VoidPaymentRequest;
import com.serfira.shared.concurrency.Sleeper;
import jakarta.persistence.OptimisticLockException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.UUID;

/**
 * Retries {@link PaymentVoidApplicationService#voidPayment} across an optimistic-lock race (E4, task T16,
 * ADR-020 D6, mirroring {@link PaymentRetryingService} / {@code ContractCreditRetryingService}): a void
 * mutates the {@code payment}, each affected {@code installment}, the {@code contract} (on a reopen) and a
 * {@code contract_credit} (on a reversal), all {@code @Version}, so it can collide with the daily servicing
 * job or a concurrent payment/credit-apply on those rows.
 *
 * <p><b>Deliberately not {@code @Transactional}.</b> This bean sits <i>outside</i>
 * {@link PaymentVoidApplicationService#voidPayment}'s transaction boundary and calls it fresh on every
 * attempt, so each attempt opens its own transaction and takes the idempotency claim again
 * ({@code IdempotencyService} is {@code Propagation.MANDATORY}). A failed attempt rolls back its claim with
 * everything else — it never consumes the key — and the next attempt observes the state the losing attempt
 * left behind.
 *
 * <p>Only an optimistic-lock failure is retryable: a void posts no new unique business row and does not
 * accrue penalty (ADR-020 D2), so there is no {@code uk_penalty_accrual} or idempotency race to classify —
 * unlike {@link PaymentRetryingService}. A hand-written loop with an injectable {@link Sleeper} is used
 * instead of {@code spring-retry}: three attempts with a fixed backoff does not justify a new dependency.
 */
@Service
public class PaymentVoidRetryingService {

	private static final Logger LOGGER = LoggerFactory.getLogger(PaymentVoidRetryingService.class);

	/** Up to 3 attempts total (first plus 2 retries); the two gaps pause 50 ms then 150 ms (as T5). */
	static final int MAX_ATTEMPTS = 3;
	static final long[] BACKOFF_MILLIS = {50L, 150L};

	private final PaymentVoidApplicationService voids;
	private final Sleeper sleeper;

	public PaymentVoidRetryingService(PaymentVoidApplicationService voids, Sleeper sleeper) {
		this.voids = voids;
		this.sleeper = sleeper;
	}

	public PaymentResponse voidPayment(UUID paymentId, String idempotencyKey, VoidPaymentRequest request) {
		RuntimeException lastFailure = null;
		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			try {
				return voids.voidPayment(paymentId, idempotencyKey, request);
			} catch (RuntimeException failure) {
				if (!isOptimisticLockConflict(failure)) {
					throw failure;
				}
				lastFailure = failure;
				if (attempt < MAX_ATTEMPTS) {
					LOGGER.warn("Retrying payment void after attempt {} ({})", attempt,
							failure.getClass().getSimpleName());
					sleeper.sleep(BACKOFF_MILLIS[attempt - 1]);
				}
			}
		}
		LOGGER.error("Payment void failed after {} attempt(s) due to a repeated conflict", MAX_ATTEMPTS);
		throw new PaymentConflictRetriesExhaustedException(MAX_ATTEMPTS, lastFailure);
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
