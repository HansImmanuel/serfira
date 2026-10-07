package com.serfira.penalty.domain;

import com.serfira.shared.audit.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.UUID;

/**
 * One waive/reduce of recognized penalty on one installment (03_DOMAIN_MODEL.md §1.10,
 * 04_GAPS_ADDENDUM.md §16.4, ADR-019 D2): the first (and only) writer of {@code penalty_adjustment},
 * owned by the {@code penalty} module.
 *
 * <p><b>Append-only.</b> A correction is a new adjustment, never an edit of history — the V1 trigger
 * {@code trg_penalty_adjustment_immutable} rejects any UPDATE/DELETE (P0001). The row therefore has no
 * setters and no {@code @Version}: {@code PenaltyAdjustmentService} only ever INSERTs a transient
 * instance, so the immutability trigger never sees an UPDATE. It extends {@link Auditable} rather than
 * {@code ImmutableAuditable} because the table carries {@code updated_at NOT NULL} (ADR-008 correction).
 *
 * <p>{@code installmentId} is a plain id, not a JPA association: {@code installment} belongs to the
 * {@code contract} module (ADR-001), so the two modules meet only through ports. The V1 foreign key still
 * protects the reference.
 *
 * <p>{@code amount} is the write-down for this row, scale-2 money, {@code > 0} (mirrors
 * {@code ck_penalty_adjustment_amount}). Summing an installment's rows gives its {@code Σ adjustment}
 * term of the effective-penalty formula (invariant 9); the V3 and V15 caps keep that sum from exceeding
 * the gross {@code penalty_amount}.
 */
@Entity
@Table(name = "penalty_adjustment")
public class PenaltyAdjustment extends Auditable {

	/** Money is scale-2 ({@code NUMERIC(19,2)}); the amount is never rounded here (TS §2.1). */
	private static final int MONEY_SCALE = 2;

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "installment_id", nullable = false, updatable = false)
	private UUID installmentId;

	@Enumerated(EnumType.STRING)
	@Column(name = "adjustment_type", nullable = false, updatable = false, length = 20)
	private PenaltyAdjustmentType adjustmentType;

	@Column(name = "amount", nullable = false, updatable = false, precision = 19, scale = 2)
	private BigDecimal amount;

	@Column(name = "reason", nullable = false, updatable = false, columnDefinition = "text")
	private String reason;

	@Column(name = "approved_by", nullable = false, updatable = false)
	private UUID approvedBy;

	protected PenaltyAdjustment() {
		// JPA
	}

	/**
	 * Records that {@code amount} of recognized penalty was {@code adjustmentType}d off {@code installmentId},
	 * approved by {@code approvedBy}, for the stated {@code reason}.
	 *
	 * @param installmentId  the installment whose effective penalty is reduced
	 * @param adjustmentType WAIVE (full) or REDUCE (partial)
	 * @param amount         scale-2 money, {@code > 0} (mirrors {@code ck_penalty_adjustment_amount})
	 * @param reason         non-blank justification kept for audit (Addendum §16.4)
	 * @param approvedBy     the {@code app_user} who approved it (the authenticated actor, never request data)
	 * @throws NullPointerException     if a reference is null
	 * @throws IllegalArgumentException if the reason is blank or the amount is not positive scale-2 money
	 * @throws ArithmeticException      if the amount is finer than scale 2
	 */
	public PenaltyAdjustment(UUID installmentId, PenaltyAdjustmentType adjustmentType, BigDecimal amount,
			String reason, UUID approvedBy) {
		this.installmentId = Objects.requireNonNull(installmentId, "installmentId");
		this.adjustmentType = Objects.requireNonNull(adjustmentType, "adjustmentType");
		this.approvedBy = Objects.requireNonNull(approvedBy, "approvedBy");
		Objects.requireNonNull(reason, "reason");
		if (reason.isBlank()) {
			throw new IllegalArgumentException("reason must not be blank");
		}
		this.reason = reason;
		Objects.requireNonNull(amount, "amount");
		BigDecimal normalized = amount.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
		if (normalized.signum() <= 0) {
			throw new IllegalArgumentException("amount must be > 0 but was " + normalized);
		}
		this.amount = normalized;
	}

	public UUID getId() {
		return id;
	}

	public UUID getInstallmentId() {
		return installmentId;
	}

	public PenaltyAdjustmentType getAdjustmentType() {
		return adjustmentType;
	}

	public BigDecimal getAmount() {
		return amount;
	}

	public String getReason() {
		return reason;
	}

	public UUID getApprovedBy() {
		return approvedBy;
	}
}
