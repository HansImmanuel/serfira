package com.serfira.contract.domain;

import com.serfira.shared.audit.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * One application of a {@link ContractCredit} to one installment (04_GAPS_ADDENDUM.md §2.2,
 * GLOSSARY credit application).
 *
 * <p>A single credit may fund many applications across installments, which is how partial application
 * keeps a full audit trail (Addendum §2.2). The row is append-only in spirit — the V14 cap trigger
 * enforces {@code Σ application.amount <= contract_credit.amount} (invariant 11) at commit — and extends
 * {@link Auditable} because the table carries all four audit columns and is not immutable.
 *
 * <p>{@code installmentId} is a plain UUID: {@code contract} owns {@code installment}, so the resolution
 * runs through its own aggregate, but the application row keeps the id rather than a JPA association to
 * avoid a second owning side of the relationship.
 */
@Entity
@Table(name = "contract_credit_application")
public class ContractCreditApplication extends Auditable {

	private static final int MONEY_SCALE = 2;

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "credit_id", nullable = false, updatable = false)
	private UUID creditId;

	@Column(name = "installment_id", nullable = false, updatable = false)
	private UUID installmentId;

	@Column(name = "amount", nullable = false, updatable = false, precision = 19, scale = 2)
	private BigDecimal amount;

	@Column(name = "applied_at", nullable = false, updatable = false, columnDefinition = "timestamptz")
	private OffsetDateTime appliedAt;

	protected ContractCreditApplication() {
		// JPA
	}

	/**
	 * Records that {@code amount} of {@code creditId} was applied to {@code installmentId} at
	 * {@code appliedAt}.
	 *
	 * @param creditId      the credit being consumed
	 * @param installmentId the installment whose recognized receivable is reduced
	 * @param amount        scale-2 money, {@code > 0} (mirrors {@code ck_contract_credit_application_amount})
	 * @param appliedAt     business instant of the application (from the server clock, Asia/Jakarta)
	 * @throws NullPointerException     if a reference is null
	 * @throws IllegalArgumentException if the amount is not positive scale-2 money
	 * @throws ArithmeticException      if the amount is finer than scale 2
	 */
	public ContractCreditApplication(UUID creditId, UUID installmentId, BigDecimal amount, OffsetDateTime appliedAt) {
		this.creditId = Objects.requireNonNull(creditId, "creditId");
		this.installmentId = Objects.requireNonNull(installmentId, "installmentId");
		this.appliedAt = Objects.requireNonNull(appliedAt, "appliedAt");
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

	public UUID getCreditId() {
		return creditId;
	}

	public UUID getInstallmentId() {
		return installmentId;
	}

	public BigDecimal getAmount() {
		return amount;
	}

	public OffsetDateTime getAppliedAt() {
		return appliedAt;
	}
}
