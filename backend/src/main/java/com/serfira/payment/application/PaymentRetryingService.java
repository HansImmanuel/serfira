package com.serfira.payment.application;

import com.serfira.payment.api.PaymentRequest;
import com.serfira.payment.api.PaymentResponse;
import com.serfira.shared.concurrency.Sleeper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Retries {@link PaymentApplicationService#create} across a job-versus-payment race (Addendum §5,
 * `docs/tasks.md` T5): a concurrent daily-servicing run and a payment can both try to accrue the same
 * {@code (installment_id, accrual_date)}, or collide on an installment's optimistic lock. The job already
 * retries its side (T3); this closes the payment side.
 *
 * <p><b>Deliberately not {@code @Transactional}.</b> This bean sits <i>outside</i>
 * {@code PaymentApplicationService.create}'s transaction boundary and calls it fresh on every attempt, so
 * each attempt opens its own transaction and takes the idempotency claim again
 * ({@code IdempotencyService} is {@code Propagation.MANDATORY} inside that transaction). A failed attempt
 * therefore rolls back its claim together with everything else — it never consumes the key — and the next
 * attempt observes the contract and installment state the losing attempt left behind, not a stale view
 * (`10-architecture`, `30-backend-java`).
 *
 * <p>A hand-written loop with an injectable {@link Sleeper} is used instead of adding {@code spring-retry}:
 * three attempts with a fixed backoff is simple enough that a new dependency is not justified
 * (`35-coding-standards` §18).
 */
@Service
public class PaymentRetryingService {

	private static final Logger LOGGER = LoggerFactory.getLogger(PaymentRetryingService.class);

	/** Addendum §5: up to 3 attempts total (the first attempt plus 2 retries), backoff 50/150/400 ms. */
	static final int MAX_ATTEMPTS = 3;
	static final long[] BACKOFF_MILLIS = {50L, 150L, 400L};

	private final PaymentApplicationService payments;
	private final Sleeper sleeper;

	public PaymentRetryingService(PaymentApplicationService payments, Sleeper sleeper) {
		this.payments = payments;
		this.sleeper = sleeper;
	}

	public PaymentResponse create(String idempotencyKey, PaymentRequest request) {
		RuntimeException lastFailure = null;
		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			try {
				return payments.create(idempotencyKey, request);
			} catch (RuntimeException failure) {
				if (!PaymentConflictClassifier.isRetryable(failure)) {
					throw failure;
				}
				lastFailure = failure;
				if (attempt < MAX_ATTEMPTS) {
					LOGGER.warn("Retrying payment create after attempt {} ({})", attempt,
							failure.getClass().getSimpleName());
					sleeper.sleep(BACKOFF_MILLIS[attempt - 1]);
				}
			}
		}
		LOGGER.error("Payment create failed after {} attempt(s) due to a repeated conflict", MAX_ATTEMPTS);
		throw new PaymentConflictRetriesExhaustedException(MAX_ATTEMPTS, lastFailure);
	}
}
