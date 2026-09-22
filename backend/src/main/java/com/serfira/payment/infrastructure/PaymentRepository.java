package com.serfira.payment.infrastructure;

import com.serfira.payment.domain.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for {@link Payment}. Uniqueness of {@code payment_no} and "one POSTED payment per
 * idempotency key" (V1 {@code uq_payment_idempotency}) are enforced by the database.
 */
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

	/** Retry-safety lookup: the payment created by an idempotent request (V1 unique index). */
	Optional<Payment> findByIdempotencyKey(String idempotencyKey);
}
