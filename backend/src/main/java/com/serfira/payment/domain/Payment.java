package com.serfira.payment.domain;

import com.serfira.shared.audit.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Money received from a customer (03_DOMAIN_MODEL.md §1.7, PRD P-1).
 *
 * <p>One row is one financial event: {@code amount} is the cash actually received and is never split
 * across rows — where the money went lives in {@link PaymentAllocation} (DM §1.8, invariant 6).
 *
 * <p>{@code contractId} is a plain id, not a {@code @ManyToOne}: the module boundary forbids the
 * {@code payment} module from traversing {@code contract}'s object graph (02_TECH_SPEC.md §1). The
 * foreign key still exists in the database.
 *
 * <p>{@code idempotencyKey} is the endpoint-scoped retry key of the request that created the row
 * (TS §2.5). It is not domain state; the database keeps one POSTED payment per key
 * ({@code uq_payment_idempotency}) as a retry-safety backstop. Voiding (E4) is a reversal: it sets
 * {@code status}/{@code voidedAt}/{@code voidReason} and never deletes the row.
 *
 * <p>{@code version} carries the optimistic lock for concurrent writes.
 */
@Entity
@Table(name = "payment", uniqueConstraints = {
		@UniqueConstraint(name = "uk_payment_no", columnNames = "payment_no")
})
public class Payment extends Auditable {

	private static final int MONEY_SCALE = 2;
	private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 80;

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "payment_no", nullable = false, length = 30)
	private String paymentNo;

	@Column(name = "contract_id", nullable = false, updatable = false)
	private UUID contractId;

	@Column(name = "amount", nullable = false, precision = 19, scale = 2)
	private BigDecimal amount;

	@Enumerated(EnumType.STRING)
	@Column(name = "channel", nullable = false, length = 20)
	private PaymentChannel channel;

	@Column(name = "paid_at", nullable = false, columnDefinition = "timestamptz")
	private OffsetDateTime paidAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private PaymentStatus status = PaymentStatus.POSTED;

	@Column(name = "voided_at", columnDefinition = "timestamptz")
	private OffsetDateTime voidedAt;

	@Column(name = "void_reason", columnDefinition = "text")
	private String voidReason;

	@Column(name = "idempotency_key", nullable = false, updatable = false, length = 80)
	private String idempotencyKey;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected Payment() {
		// JPA
	}

	/**
	 * Records a received payment as POSTED.
	 *
	 * <p>The guards mirror the V1 CHECK constraints ({@code amount > 0}, channel/status vocabulary) so an
	 * invalid payment cannot be built in memory and then rejected at COMMIT.
	 *
	 * @param paymentNo      generated business number ({@code PAY-YYYYMM-XXXX})
	 * @param contractId     contract the money belongs to
	 * @param amount         scale-2 money, {@code > 0}; a value finer than scale 2 is rejected instead of
	 *                       being silently rounded
	 * @param channel        how the money arrived
	 * @param paidAt         business instant from the application clock — never client supplied (TS §2.0)
	 * @param idempotencyKey endpoint-scoped retry key of the request; required ({@code uq_payment_idempotency})
	 */
	public Payment(String paymentNo, UUID contractId, BigDecimal amount, PaymentChannel channel,
			OffsetDateTime paidAt, String idempotencyKey) {
		this.paymentNo = requireText(paymentNo, "paymentNo");
		this.contractId = Objects.requireNonNull(contractId, "contractId");
		this.amount = money(amount);
		this.channel = Objects.requireNonNull(channel, "channel");
		this.paidAt = Objects.requireNonNull(paidAt, "paidAt");
		this.idempotencyKey = requireKey(idempotencyKey);
		this.status = PaymentStatus.POSTED;
	}

	public UUID getId() {
		return id;
	}

	public String getPaymentNo() {
		return paymentNo;
	}

	public UUID getContractId() {
		return contractId;
	}

	/** Total cash received; always scale-2 and positive. */
	public BigDecimal getAmount() {
		return amount;
	}

	public PaymentChannel getChannel() {
		return channel;
	}

	/** Business instant of the payment, taken from the application clock at posting time. */
	public OffsetDateTime getPaidAt() {
		return paidAt;
	}

	public PaymentStatus getStatus() {
		return status;
	}

	public OffsetDateTime getVoidedAt() {
		return voidedAt;
	}

	public String getVoidReason() {
		return voidReason;
	}

	public String getIdempotencyKey() {
		return idempotencyKey;
	}

	public long getVersion() {
		return version;
	}

	private static BigDecimal money(BigDecimal value) {
		Objects.requireNonNull(value, "amount");
		BigDecimal normalized;
		try {
			normalized = value.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
		} catch (ArithmeticException ex) {
			throw new IllegalArgumentException("amount must be money with at most " + MONEY_SCALE
					+ " decimal places but was " + value, ex);
		}
		if (normalized.signum() <= 0) {
			throw new IllegalArgumentException("amount must be > 0 but was " + normalized);
		}
		return normalized;
	}

	private static String requireKey(String idempotencyKey) {
		String key = requireText(idempotencyKey, "idempotencyKey");
		if (key.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
			throw new IllegalArgumentException("idempotencyKey must be at most " + MAX_IDEMPOTENCY_KEY_LENGTH
					+ " characters but had " + key.length());
		}
		return key;
	}

	private static String requireText(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(name + " is required");
		}
		return value;
	}
}
