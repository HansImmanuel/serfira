package com.serfira.payment.domain;

import com.serfira.shared.audit.ImmutableAuditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.Objects;
import java.util.UUID;

/**
 * Durable single-use-forever backstop for a payment-void idempotency key (T31, ADR-017, ADR-020 D3/D6,
 * closes ADR-020 "Deferred finding #5").
 *
 * <p>V18's all-status {@code uq_payment_idempotency} keeps a payment-<i>creation</i> key spent forever. A
 * payment-<i>void</i> key is a different, endpoint-scoped key that lived only in the {@code idempotency_keys}
 * claim row, which the T18 cleanup job deletes once it expires. After that deletion the key could be reused
 * to void a <b>different</b> payment. One row of this append-only table is written inside the void
 * transaction (ADR-020 D6) and records the endpoint-scoped key → voided-payment association permanently; the
 * {@code uq_payment_void_idempotency} UNIQUE {@code (endpoint, idempotency_key)} constraint (V22) is the
 * single-use-forever authority once the claim row is gone.
 *
 * <p>Append-only: the row is written once and never updated or deleted (extends {@link ImmutableAuditable}).
 * The guards mirror the V22 NOT NULL columns so an invalid association cannot be built in memory and then
 * rejected at COMMIT (the two-layer invariant pattern).
 */
@Entity
@Table(name = "payment_void_idempotency", uniqueConstraints = {
		@UniqueConstraint(name = "uq_payment_void_idempotency", columnNames = {"endpoint", "idempotency_key"})
})
public class PaymentVoidIdempotency extends ImmutableAuditable {

	private static final int MAX_ENDPOINT_LENGTH = 120;
	private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 80;

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "endpoint", nullable = false, updatable = false, length = 120)
	private String endpoint;

	@Column(name = "idempotency_key", nullable = false, updatable = false, length = 80)
	private String idempotencyKey;

	@Column(name = "payment_id", nullable = false, updatable = false)
	private UUID paymentId;

	protected PaymentVoidIdempotency() {
		// JPA
	}

	/**
	 * Records that one void idempotency key has voided one payment, durably.
	 *
	 * @param endpoint       the void endpoint scope (TS §2.2: keys are endpoint-scoped)
	 * @param idempotencyKey the endpoint-scoped retry key of the void request; required
	 *                       ({@code uq_payment_void_idempotency})
	 * @param paymentId      the payment this key voided
	 */
	public PaymentVoidIdempotency(String endpoint, String idempotencyKey, UUID paymentId) {
		this.endpoint = requireText(endpoint, "endpoint", MAX_ENDPOINT_LENGTH);
		this.idempotencyKey = requireText(idempotencyKey, "idempotencyKey", MAX_IDEMPOTENCY_KEY_LENGTH);
		this.paymentId = Objects.requireNonNull(paymentId, "paymentId");
	}

	public UUID getId() {
		return id;
	}

	public String getEndpoint() {
		return endpoint;
	}

	public String getIdempotencyKey() {
		return idempotencyKey;
	}

	public UUID getPaymentId() {
		return paymentId;
	}

	private static String requireText(String value, String name, int maxLength) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(name + " is required");
		}
		String trimmed = value.trim();
		if (trimmed.length() > maxLength) {
			throw new IllegalArgumentException(name + " must be at most " + maxLength + " characters but had "
					+ trimmed.length());
		}
		return trimmed;
	}
}
