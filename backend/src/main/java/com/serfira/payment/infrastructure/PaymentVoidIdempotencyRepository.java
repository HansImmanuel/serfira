package com.serfira.payment.infrastructure;

import com.serfira.payment.domain.PaymentVoidIdempotency;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Persistence for {@link PaymentVoidIdempotency}, the durable single-use-forever backstop for void keys
 * (T31). Uniqueness of {@code (endpoint, idempotency_key)} is enforced by the database
 * ({@code uq_payment_void_idempotency}, V22), so a reused void key fails at flush rather than needing an
 * application-side pre-check (the concurrency-safe backstop, 60-security / 35-coding-standards).
 */
public interface PaymentVoidIdempotencyRepository extends JpaRepository<PaymentVoidIdempotency, UUID> {
}
