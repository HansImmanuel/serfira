package com.serfira.settlement.domain;

import com.serfira.shared.audit.Auditable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * The immutable record of an executed early settlement (E2, task T13, DM §1.6, ADR-018). Created once when
 * a QUOTED quote is executed; the V5 {@code trg_settlement_immutable} trigger blocks every UPDATE/DELETE,
 * so there is no settlement void or correction in the MVP (ADR-018 D1). The constructor guards mirror the
 * V1 {@code ck_settlement_amounts} CHECK ({@code cash_received}, {@code credit_used}, {@code rebate_amount},
 * {@code admin_fee} all {@code >= 0}).
 *
 * <p>No cross-module JPA associations: {@code contractId} is a plain UUID (02_TECH_SPEC.md §1); the DB FK
 * to {@code contract} exists but the entity never navigates to another module's aggregate. {@code quoteId}
 * is a plain UUID into this module's own {@code settlement_quote}. {@code uk_settlement_quote_id} (V1) is
 * the DB backstop for one settlement per quote.
 */
@Entity
@Table(name = "settlement")
public class Settlement extends Auditable {

	private static final int MONEY_SCALE = 2;

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "settlement_no", nullable = false, length = 40, updatable = false)
	private String settlementNo;

	@Column(name = "contract_id", nullable = false, updatable = false)
	private UUID contractId;

	@Column(name = "quote_id", nullable = false, updatable = false)
	private UUID quoteId;

	@Column(name = "cash_received", nullable = false, precision = 19, scale = 2, updatable = false)
	private BigDecimal cashReceived;

	@Column(name = "credit_used", nullable = false, precision = 19, scale = 2, updatable = false)
	private BigDecimal creditUsed;

	@Column(name = "rebate_amount", nullable = false, precision = 19, scale = 2, updatable = false)
	private BigDecimal rebateAmount;

	@Column(name = "admin_fee", nullable = false, precision = 19, scale = 2, updatable = false)
	private BigDecimal adminFee;

	@Column(name = "executed_at", nullable = false, columnDefinition = "timestamptz", updatable = false)
	private OffsetDateTime executedAt;

	@Column(name = "idempotency_key", nullable = false, length = 80, updatable = false)
	private String idempotencyKey;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected Settlement() {
		// JPA
	}

	/**
	 * Records one executed settlement.
	 *
	 * @param settlementNo   business number ({@code SET-YYYYMM-XXXX})
	 * @param contractId     the settled contract (plain UUID; cross-module)
	 * @param quoteId        the executed quote
	 * @param cashReceived   cash the customer paid ({@code gross_amount − credit_used})
	 * @param creditUsed     AVAILABLE customer credit consumed (ADR-018 D10)
	 * @param rebateAmount   the rebate on unearned future interest, carried for the record (never charged)
	 * @param adminFee       the flat settlement admin fee
	 * @param executedAt     business instant of execution (Asia/Jakarta)
	 * @param idempotencyKey the request's {@code Idempotency-Key}
	 * @throws NullPointerException     if any argument is null
	 * @throws IllegalArgumentException if a monetary amount is negative or finer than scale 2
	 */
	public Settlement(String settlementNo, UUID contractId, UUID quoteId, BigDecimal cashReceived,
			BigDecimal creditUsed, BigDecimal rebateAmount, BigDecimal adminFee, OffsetDateTime executedAt,
			String idempotencyKey) {
		this.settlementNo = Objects.requireNonNull(settlementNo, "settlementNo");
		this.contractId = Objects.requireNonNull(contractId, "contractId");
		this.quoteId = Objects.requireNonNull(quoteId, "quoteId");
		this.cashReceived = money(cashReceived, "cash_received");
		this.creditUsed = money(creditUsed, "credit_used");
		this.rebateAmount = money(rebateAmount, "rebate_amount");
		this.adminFee = money(adminFee, "admin_fee");
		this.executedAt = Objects.requireNonNull(executedAt, "executedAt");
		this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
	}

	private static BigDecimal money(BigDecimal value, String name) {
		Objects.requireNonNull(value, name);
		if (value.scale() > MONEY_SCALE) {
			throw new IllegalArgumentException(name + " must be money with at most " + MONEY_SCALE
					+ " decimal places but was " + value);
		}
		if (value.signum() < 0) {
			throw new IllegalArgumentException(name + " must be >= 0 but was " + value);
		}
		return value.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
	}

	public UUID getId() {
		return id;
	}

	public String getSettlementNo() {
		return settlementNo;
	}

	public UUID getContractId() {
		return contractId;
	}

	public UUID getQuoteId() {
		return quoteId;
	}

	public BigDecimal getCashReceived() {
		return cashReceived;
	}

	public BigDecimal getCreditUsed() {
		return creditUsed;
	}

	public BigDecimal getRebateAmount() {
		return rebateAmount;
	}

	public BigDecimal getAdminFee() {
		return adminFee;
	}

	public OffsetDateTime getExecutedAt() {
		return executedAt;
	}

	public String getIdempotencyKey() {
		return idempotencyKey;
	}

	public long getVersion() {
		return version;
	}
}
