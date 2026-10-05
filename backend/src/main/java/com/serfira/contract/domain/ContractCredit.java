package com.serfira.contract.domain;

import com.serfira.shared.audit.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable customer credit born from an overpayment (04_GAPS_ADDENDUM.md §2.1, GLOSSARY ContractCredit).
 *
 * <p>When a payment produces an EXCESS allocation the {@code payment} module records the money as a
 * {@code TITIPAN_NASABAH} journal credit; this row is the <b>liability sub-ledger</b> that tracks the
 * same money as a reusable balance, never a second journal entry (task T14, Addendum §2). The credit is
 * applied to recognized receivable only on an explicit call — it is never auto-applied (invariant 6,
 * PRD P-4).
 *
 * <p>{@code amount} is immutable; the only mutations are {@code status}/{@code version}/audit columns
 * (neither {@code contract_credit} nor {@code contract_credit_application} is in the V1/V5 immutability
 * trigger set), so this entity extends {@link Auditable}, not {@code ImmutableAuditable}. Cross-module
 * references ({@code contractId}, {@code sourcePaymentAllocationId}) stay plain UUIDs: {@code contract}
 * must not hold a JPA association to {@code payment}'s {@code payment_allocation} (02_TECH_SPEC.md §1).
 *
 * <p>{@code uk_contract_credit_source} (V1) enforces at most one credit per EXCESS allocation, so a
 * retried payment can never book the same excess twice.
 */
@Entity
@Table(name = "contract_credit")
public class ContractCredit extends Auditable {

	/** Money is scale-2 ({@code NUMERIC(19,2)}); the amount is never rounded. */
	private static final int MONEY_SCALE = 2;

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "contract_id", nullable = false, updatable = false)
	private UUID contractId;

	@Column(name = "source_payment_allocation_id", nullable = false, updatable = false)
	private UUID sourcePaymentAllocationId;

	@Column(name = "amount", nullable = false, updatable = false, precision = 19, scale = 2)
	private BigDecimal amount;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private ContractCreditStatus status = ContractCreditStatus.AVAILABLE;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected ContractCredit() {
		// JPA
	}

	/**
	 * Books a new AVAILABLE credit from an EXCESS allocation.
	 *
	 * @param contractId                contract the credit belongs to
	 * @param sourcePaymentAllocationId the EXCESS {@code payment_allocation} id this credit came from
	 * @param amount                    the excess; scale-2 money, {@code > 0} (mirrors
	 *                                  {@code ck_contract_credit_amount})
	 * @throws NullPointerException     if a reference is null
	 * @throws IllegalArgumentException if the amount is not positive scale-2 money
	 * @throws ArithmeticException      if the amount is finer than scale 2
	 */
	public ContractCredit(UUID contractId, UUID sourcePaymentAllocationId, BigDecimal amount) {
		this.contractId = Objects.requireNonNull(contractId, "contractId");
		this.sourcePaymentAllocationId = Objects.requireNonNull(sourcePaymentAllocationId,
				"sourcePaymentAllocationId");
		this.amount = positiveMoney(amount);
		this.status = ContractCreditStatus.AVAILABLE;
	}

	/**
	 * Records that {@code applied} of this credit was consumed, leaving {@code balanceAfter} available,
	 * and flips {@code AVAILABLE → APPLIED} exactly when the balance reaches 0 — the status rule
	 * (Addendum §2.1), expressed as an intention-revealing method rather than a status setter.
	 *
	 * <p>The caller computes {@code balanceAfter} from the credit's applications; this method only
	 * guards the transition and keeps {@code amount} frozen. Mirrors the V14 status guard trigger.
	 *
	 * @param applied      the amount consumed in this call; scale-2 money, {@code > 0}
	 * @param balanceAfter available balance after this call ({@code amount − Σ applications}); {@code >= 0}
	 * @throws IllegalStateException    if the credit is not AVAILABLE
	 * @throws IllegalArgumentException if the amounts are not valid scale-2 money, or the balance is
	 *                                  negative or above {@code amount}
	 */
	public void recordApplication(BigDecimal applied, BigDecimal balanceAfter) {
		if (status != ContractCreditStatus.AVAILABLE) {
			throw new IllegalStateException("credit " + id + " is " + status + " and can fund no further application");
		}
		BigDecimal consumed = positiveMoney(applied);
		BigDecimal remaining = money(balanceAfter);
		if (remaining.signum() < 0) {
			throw new IllegalArgumentException("balanceAfter must be >= 0 but was " + remaining);
		}
		if (remaining.compareTo(amount) > 0) {
			throw new IllegalArgumentException("balanceAfter " + remaining + " exceeds credit amount " + amount);
		}
		if (consumed.compareTo(amount) > 0) {
			throw new IllegalArgumentException("applied " + consumed + " exceeds credit amount " + amount);
		}
		if (remaining.signum() == 0) {
			this.status = ContractCreditStatus.APPLIED;
		}
	}

	public UUID getId() {
		return id;
	}

	public UUID getContractId() {
		return contractId;
	}

	public UUID getSourcePaymentAllocationId() {
		return sourcePaymentAllocationId;
	}

	public BigDecimal getAmount() {
		return amount;
	}

	public ContractCreditStatus getStatus() {
		return status;
	}

	public long getVersion() {
		return version;
	}

	private static BigDecimal positiveMoney(BigDecimal value) {
		BigDecimal normalized = money(value);
		if (normalized.signum() <= 0) {
			throw new IllegalArgumentException("amount must be > 0 but was " + normalized);
		}
		return normalized;
	}

	private static BigDecimal money(BigDecimal value) {
		Objects.requireNonNull(value, "amount");
		return value.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
	}
}
