package com.serfira.payment.infrastructure;

import com.serfira.payment.domain.PaymentVoidIdempotency;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for {@link PaymentVoidIdempotency}, the durable single-use-forever backstop for void keys
 * (T31). Uniqueness of {@code (endpoint, idempotency_key)} is enforced by the database
 * ({@code uq_payment_void_idempotency}, V22), so a reused void key fails at flush rather than needing an
 * application-side pre-check (the concurrency-safe backstop, 60-security / 35-coding-standards).
 *
 * <p>{@link #findByEndpointAndIdempotencyKey} is <b>not</b> the concurrency authority — the UNIQUE
 * constraint still is. It only lets the already-VOIDED no-op path decide whether a committed row maps this
 * key to <i>this</i> payment (a legitimate replay) or to a <i>different</i> one (a spent key, reject), which
 * a blind re-insert could not distinguish without wrongly tripping the constraint on a legitimate replay
 * (T31, PR #16 finding 1).
 */
public interface PaymentVoidIdempotencyRepository extends JpaRepository<PaymentVoidIdempotency, UUID> {

	Optional<PaymentVoidIdempotency> findByEndpointAndIdempotencyKey(String endpoint, String idempotencyKey);
}
