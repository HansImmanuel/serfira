package com.serfira.settlement.domain;

import com.serfira.shared.audit.Auditable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.UUID;

/**
 * Which {@code contract_credit} source a settlement consumed, and how much (E2, task T13, ADR-018 D5/D10,
 * DM §1.6). One row per consumed source credit; {@code uk_settlement_credit_application
 * (settlement_id, contract_credit_id)} (V1) keeps a source from being recorded twice for one settlement.
 *
 * <p>This is the settlement-side consumption record. The {@code contract} module flips the consumed
 * {@code contract_credit} to {@code APPLIED}; the V17 migration teaches the V14 status/cap guards to count
 * these rows so the credit's balance derivation stays coherent across both the regular-apply and the
 * settlement path. {@code settlementId} is a plain UUID into this module; {@code contractCreditId} is a
 * cross-module id whose DB FK to {@code contract_credit} exists without a JPA association
 * (02_TECH_SPEC.md §1).
 */
@Entity
@Table(name = "settlement_credit_application")
public class SettlementCreditApplication extends Auditable {

	private static final int MONEY_SCALE = 2;

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "settlement_id", nullable = false, updatable = false)
	private UUID settlementId;

	@Column(name = "contract_credit_id", nullable = false, updatable = false)
	private UUID contractCreditId;

	@Column(name = "amount", nullable = false, precision = 19, scale = 2, updatable = false)
	private BigDecimal amount;

	protected SettlementCreditApplication() {
		// JPA
	}

	/**
	 * Records that {@code amount} of {@code contractCreditId} was consumed by {@code settlementId}.
	 *
	 * @param settlementId     the settlement
	 * @param contractCreditId the consumed credit source
	 * @param amount           scale-2 money, {@code > 0} (mirrors {@code ck_settlement_credit_application_amount})
	 * @throws NullPointerException     if a reference is null
	 * @throws IllegalArgumentException if the amount is not positive scale-2 money
	 * @throws ArithmeticException      if the amount is finer than scale 2
	 */
	public SettlementCreditApplication(UUID settlementId, UUID contractCreditId, BigDecimal amount) {
		this.settlementId = Objects.requireNonNull(settlementId, "settlementId");
		this.contractCreditId = Objects.requireNonNull(contractCreditId, "contractCreditId");
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

	public UUID getContractCreditId() {
		return contractCreditId;
	}

	public BigDecimal getAmount() {
		return amount;
	}
}
