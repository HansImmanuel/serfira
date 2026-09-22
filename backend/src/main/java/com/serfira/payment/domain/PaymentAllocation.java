package com.serfira.payment.domain;

import com.serfira.payment.domain.allocation.AllocationLine;
import com.serfira.shared.audit.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.UUID;

/**
 * Where one payment's money went (03_DOMAIN_MODEL.md §1.8, PRD P-2/P-3/P-4).
 *
 * <p>One row per produced {@link AllocationLine}: {@code PENALTY}/{@code INTEREST}/{@code PRINCIPAL}
 * resolve one installment, {@code EXCESS} carries no installment and becomes customer credit.
 *
 * <p><b>Append-only.</b> A row is never updated or deleted: after a payment is voided (E4) the row stays
 * as history and merely stops counting as <b>active</b> (DM §1.8), which is why
 * {@code allocated*} amounts are always a sum over the allocations of POSTED payments only.
 *
 * <p>{@code installmentId} is a plain id — the {@code installment} table belongs to the {@code contract}
 * module (02_TECH_SPEC.md §1). Only the {@code contract} module may resolve an installment.
 */
@Entity
@Table(name = "payment_allocation")
public class PaymentAllocation extends Auditable {

	private static final int MONEY_SCALE = 2;

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "payment_id", nullable = false, updatable = false)
	private Payment payment;

	@Column(name = "installment_id", updatable = false)
	private UUID installmentId;

	@Enumerated(EnumType.STRING)
	@Column(name = "allocation_type", nullable = false, updatable = false, length = 20)
	private AllocationType type;

	@Column(name = "amount", nullable = false, updatable = false, precision = 19, scale = 2)
	private BigDecimal amount;

	protected PaymentAllocation() {
		// JPA
	}

	/**
	 * Materializes one allocation line of a posted payment.
	 *
	 * <p>The guards mirror V1's CHECK constraints ({@code amount > 0}, {@code (type = 'EXCESS') =
	 * (installment_id IS NULL)}), so a row the database would reject can never be built.
	 */
	public static PaymentAllocation of(Payment payment, AllocationLine line) {
		Objects.requireNonNull(line, "line");
		return new PaymentAllocation(payment, line.installmentRef(), line.type(), line.amount());
	}

	private PaymentAllocation(Payment payment, UUID installmentId, AllocationType type, BigDecimal amount) {
		this.payment = Objects.requireNonNull(payment, "payment");
		this.type = Objects.requireNonNull(type, "type");
		this.amount = money(amount);
		if ((type == AllocationType.EXCESS) != (installmentId == null)) {
			throw new IllegalArgumentException("only an EXCESS allocation may omit the installment, but got "
					+ type + " with installmentId = " + installmentId);
		}
		this.installmentId = installmentId;
	}

	public UUID getId() {
		return id;
	}

	public Payment getPayment() {
		return payment;
	}

	/** Installment this amount resolved; {@code null} exactly for {@link AllocationType#EXCESS}. */
	public UUID getInstallmentId() {
		return installmentId;
	}

	public AllocationType getType() {
		return type;
	}

	public BigDecimal getAmount() {
		return amount;
	}

	private static BigDecimal money(BigDecimal value) {
		Objects.requireNonNull(value, "amount");
		BigDecimal normalized = value.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
		if (normalized.signum() <= 0) {
			throw new IllegalArgumentException("allocation amount must be > 0 but was " + normalized);
		}
		return normalized;
	}
}
