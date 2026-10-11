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
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * An immutable priced settlement quote (E1, task T12, DM §1.5, ADR-018). Every component column is frozen
 * at creation by the V5 {@code trg_settlement_quote_mutability} trigger — only {@code status} (and the
 * audit/version columns) may change, when T13 executes or expires the quote. The constructor guards
 * mirror the V1 CHECK constraints ({@code ck_settlement_quote_amounts}, {@code ck_settlement_quote_credit_used})
 * so a broken quote fails in Java before it reaches the database.
 *
 * <p>No cross-module JPA associations: {@code contractId} is a plain UUID (the DB FK to {@code contract}
 * exists, but the entity never navigates to another module's aggregate — 02_TECH_SPEC.md §1).
 */
@Entity
@Table(name = "settlement_quote")
public class SettlementQuote extends Auditable {

	private static final int MONEY_SCALE = 2;

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "quote_no", nullable = false, length = 40, updatable = false)
	private String quoteNo;

	@Column(name = "contract_id", nullable = false, updatable = false)
	private UUID contractId;

	@Column(name = "quoted_at", nullable = false, updatable = false, columnDefinition = "timestamptz")
	private OffsetDateTime quotedAt;

	@Column(name = "valid_until", nullable = false, updatable = false, columnDefinition = "timestamptz")
	private OffsetDateTime validUntil;

	@Column(name = "contract_version", nullable = false, updatable = false)
	private long contractVersion;

	@Column(name = "outstanding_principal", nullable = false, precision = 19, scale = 2, updatable = false)
	private BigDecimal outstandingPrincipal;

	@Column(name = "unpaid_billed_interest", nullable = false, precision = 19, scale = 2, updatable = false)
	private BigDecimal unpaidBilledInterest;

	@Column(name = "accrued_interest", nullable = false, precision = 19, scale = 2, updatable = false)
	private BigDecimal accruedInterest;

	@Column(name = "penalty_outstanding", nullable = false, precision = 19, scale = 2, updatable = false)
	private BigDecimal penaltyOutstanding;

	@Column(name = "rebate_amount", nullable = false, precision = 19, scale = 2, updatable = false)
	private BigDecimal rebateAmount;

	@Column(name = "admin_fee", nullable = false, precision = 19, scale = 2, updatable = false)
	private BigDecimal adminFee;

	@Column(name = "available_credit", nullable = false, precision = 19, scale = 2, updatable = false)
	private BigDecimal availableCredit;

	@Column(name = "credit_used", nullable = false, precision = 19, scale = 2, updatable = false)
	private BigDecimal creditUsed;

	@Column(name = "gross_amount", nullable = false, precision = 19, scale = 2, updatable = false)
	private BigDecimal grossAmount;

	@Column(name = "cash_due", nullable = false, precision = 19, scale = 2, updatable = false)
	private BigDecimal cashDue;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private SettlementQuoteStatus status = SettlementQuoteStatus.QUOTED;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected SettlementQuote() {
		// JPA
	}

	/**
	 * Creates a QUOTED settlement quote.
	 *
	 * @param quoteNo         business number ({@code Q-YYYYMMDD-XXXX})
	 * @param contractId      the contract this prices (plain UUID; cross-module)
	 * @param quotedAt        business instant the quote was priced
	 * @param validUntil      TTL cutoff ({@code quotedAt + SETTLEMENT_QUOTE_TTL_MINUTES})
	 * @param contractVersion the contract's {@code @Version} at pricing time (ADR-018 D9 staleness check)
	 * @param components      the priced components (ADR-018 D6)
	 * @throws NullPointerException     if any argument is null
	 * @throws IllegalArgumentException if a component amount is negative, finer than scale 2, or
	 *                                  {@code credit_used > available_credit}, or {@code valid_until} is not
	 *                                  after {@code quoted_at}
	 */
	public SettlementQuote(String quoteNo, UUID contractId, OffsetDateTime quotedAt, OffsetDateTime validUntil,
			long contractVersion, SettlementQuoteComponents components) {
		this.quoteNo = Objects.requireNonNull(quoteNo, "quoteNo");
		this.contractId = Objects.requireNonNull(contractId, "contractId");
		this.quotedAt = Objects.requireNonNull(quotedAt, "quotedAt");
		this.validUntil = Objects.requireNonNull(validUntil, "validUntil");
		Objects.requireNonNull(components, "components");
		if (!validUntil.isAfter(quotedAt)) {
			throw new IllegalArgumentException("valid_until must be after quoted_at");
		}
		this.contractVersion = contractVersion;
		this.outstandingPrincipal = money(components.outstandingPrincipal(), "outstanding_principal");
		this.unpaidBilledInterest = money(components.unpaidBilledInterest(), "unpaid_billed_interest");
		this.accruedInterest = money(components.accruedInterest(), "accrued_interest");
		this.penaltyOutstanding = money(components.penaltyOutstanding(), "penalty_outstanding");
		this.rebateAmount = money(components.rebateAmount(), "rebate_amount");
		this.adminFee = money(components.adminFee(), "admin_fee");
		this.availableCredit = money(components.availableCredit(), "available_credit");
		this.creditUsed = money(components.creditUsed(), "credit_used");
		this.grossAmount = money(components.grossAmount(), "gross_amount");
		this.cashDue = money(components.cashDue(), "cash_due");
		if (this.creditUsed.compareTo(this.availableCredit) > 0) {
			throw new IllegalArgumentException("credit_used must be <= available_credit");
		}
		this.status = SettlementQuoteStatus.QUOTED;
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

	/**
	 * Transitions the quote {@code QUOTED → EXECUTED} when it is executed into a settlement (E2, task T13,
	 * DM §1.5). This is the only mutation the V5 {@code trg_settlement_quote_mutability} trigger permits
	 * besides the audit/version columns — every component snapshot column stays frozen. A quote executes at
	 * most once; executing a non-QUOTED quote is a programming error the caller already guards (it rejects
	 * an EXECUTED quote with {@code SETTLEMENT_QUOTE_ALREADY_EXECUTED} and an EXPIRED one with
	 * {@code SETTLEMENT_QUOTE_EXPIRED} before reaching here, ADR-018 D9).
	 *
	 * @throws IllegalStateException if the quote is not QUOTED
	 */
	public void markExecuted() {
		if (status != SettlementQuoteStatus.QUOTED) {
			throw new IllegalStateException("settlement quote " + quoteNo + " is " + status
					+ " and cannot be executed");
		}
		this.status = SettlementQuoteStatus.EXECUTED;
	}

	public UUID getId() {
		return id;
	}

	public String getQuoteNo() {
		return quoteNo;
	}

	public UUID getContractId() {
		return contractId;
	}

	public OffsetDateTime getQuotedAt() {
		return quotedAt;
	}

	public OffsetDateTime getValidUntil() {
		return validUntil;
	}

	public long getContractVersion() {
		return contractVersion;
	}

	public BigDecimal getOutstandingPrincipal() {
		return outstandingPrincipal;
	}

	public BigDecimal getUnpaidBilledInterest() {
		return unpaidBilledInterest;
	}

	public BigDecimal getAccruedInterest() {
		return accruedInterest;
	}

	public BigDecimal getPenaltyOutstanding() {
		return penaltyOutstanding;
	}

	public BigDecimal getRebateAmount() {
		return rebateAmount;
	}

	public BigDecimal getAdminFee() {
		return adminFee;
	}

	public BigDecimal getAvailableCredit() {
		return availableCredit;
	}

	public BigDecimal getCreditUsed() {
		return creditUsed;
	}

	public BigDecimal getGrossAmount() {
		return grossAmount;
	}

	public BigDecimal getCashDue() {
		return cashDue;
	}

	public SettlementQuoteStatus getStatus() {
		return status;
	}

	public long getVersion() {
		return version;
	}
}
