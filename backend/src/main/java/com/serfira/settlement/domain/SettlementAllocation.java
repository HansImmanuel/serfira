package com.serfira.settlement.domain;

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
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * One installment's receivable resolution by a settlement (E2, task T13, ADR-018 D5). The settlement
 * analogue of {@code payment_allocation}: a row per installment-and-component the settlement cleared,
 * {@code allocation_type ∈ {PENALTY, INTEREST, PRINCIPAL}}, {@code amount > 0}. Income, admin fee, rebate
 * and credit consumption are <b>not</b> allocations (they are journal lines and settlement columns).
 *
 * <p>Immutable: the V1 {@code trg_settlement_allocation_immutable} trigger blocks every UPDATE/DELETE, so
 * corrections would be reversals, never edits. {@code settlementId} and {@code installmentId} are plain
 * UUIDs — {@code settlement_id} into this module, {@code installment_id} a cross-module id whose DB FK
 * exists without a JPA association (02_TECH_SPEC.md §1).
 */
@Entity
@Table(name = "settlement_allocation")
public class SettlementAllocation extends Auditable {

	private static final int MONEY_SCALE = 2;

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "settlement_id", nullable = false, updatable = false)
	private UUID settlementId;

	@Column(name = "installment_id", nullable = false, updatable = false)
	private UUID installmentId;

	@Enumerated(EnumType.STRING)
	@Column(name = "allocation_type", nullable = false, length = 20, updatable = false)
	private SettlementAllocationType allocationType;

	@Column(name = "amount", nullable = false, precision = 19, scale = 2, updatable = false)
	private BigDecimal amount;

	protected SettlementAllocation() {
		// JPA
	}

	/**
	 * Records that {@code amount} of {@code installmentId}'s {@code allocationType} receivable was resolved
	 * by {@code settlementId}.
	 *
	 * @param settlementId   the settlement
	 * @param installmentId  the installment whose recognized receivable is reduced
	 * @param allocationType the resolved component
	 * @param amount         scale-2 money, {@code > 0} (mirrors {@code ck_settlement_allocation_amount})
	 * @throws NullPointerException     if a reference is null
	 * @throws IllegalArgumentException if the amount is not positive scale-2 money
	 * @throws ArithmeticException      if the amount is finer than scale 2
	 */
	public SettlementAllocation(UUID settlementId, UUID installmentId, SettlementAllocationType allocationType,
			BigDecimal amount) {
		this.settlementId = Objects.requireNonNull(settlementId, "settlementId");
		this.installmentId = Objects.requireNonNull(installmentId, "installmentId");
		this.allocationType = Objects.requireNonNull(allocationType, "allocationType");
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

	public UUID getSettlementId() {
		return settlementId;
	}

	public UUID getInstallmentId() {
		return installmentId;
	}

	public SettlementAllocationType getAllocationType() {
		return allocationType;
	}

	public BigDecimal getAmount() {
		return amount;
	}
}
