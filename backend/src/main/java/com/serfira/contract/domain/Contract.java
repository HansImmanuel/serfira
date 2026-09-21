package com.serfira.contract.domain;

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
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A financing agreement between one customer and one financed asset (03_DOMAIN_MODEL.md §1.3).
 *
 * <p>{@code plannedStartDate} is the date the operator authored while drafting (PRD C-1);
 * {@code startDate} is the <b>effective</b> activation date that pins the schedule. A DRAFT row
 * therefore keeps {@code startDate} NULL (V4 {@code ck_contract_draft_coherence}) and only carries
 * the planned date — see ADR-006.
 *
 * <p>{@code gracePeriodDays} and {@code penaltyRateDaily} are snapshots of the global configuration
 * (Addendum §1.1), never live references — configuration changes must not alter running contracts
 * retroactively. A DRAFT row holds the values in force when it was drafted; activation re-snapshots
 * them, because the values that matter are the ones in force when the contract went live.
 * {@code idempotencyKey} is the endpoint-scoped retry key of the creating request (TS §2.5) and is
 * not part of the domain state. {@code version} carries the optimistic lock for concurrent writes.
 */
@Entity
@Table(name = "contract", uniqueConstraints = {
		@UniqueConstraint(name = "uk_contract_no", columnNames = "contract_no")
})
public class Contract extends Auditable {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "contract_no", nullable = false, length = 30)
	private String contractNo;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "customer_id", nullable = false)
	private Customer customer;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "asset_id", nullable = false)
	private Asset asset;

	@Column(name = "asset_price", nullable = false, precision = 19, scale = 2)
	private BigDecimal assetPrice;

	@Column(name = "principal", nullable = false, precision = 19, scale = 2)
	private BigDecimal principal;

	@Column(name = "down_payment", nullable = false, precision = 19, scale = 2)
	private BigDecimal downPayment;

	@Column(name = "tenor_months", nullable = false)
	private int tenorMonths;

	@Enumerated(EnumType.STRING)
	@Column(name = "interest_scheme", nullable = false, length = 20)
	private InterestScheme interestScheme;

	@Column(name = "interest_rate", nullable = false, precision = 7, scale = 4)
	private BigDecimal interestRate;

	@Column(name = "grace_period_days", nullable = false)
	private int gracePeriodDays;

	@Column(name = "penalty_rate_daily", nullable = false, precision = 7, scale = 4)
	private BigDecimal penaltyRateDaily;

	@Column(name = "planned_start_date")
	private LocalDate plannedStartDate;

	@Column(name = "start_date")
	private LocalDate startDate;

	@Column(name = "idempotency_key", length = 80)
	private String idempotencyKey;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private ContractStatus status;

	@Column(name = "write_off_reason", columnDefinition = "text")
	private String writeOffReason;

	@Column(name = "write_off_recorded_by")
	private UUID writeOffRecordedBy;

	@Column(name = "closed_at", columnDefinition = "timestamptz")
	private OffsetDateTime closedAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "closed_reason", length = 20)
	private ClosedReason closedReason;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected Contract() {
		// JPA
	}

	/**
	 * Creates a DRAFT contract. The schedule is generated at activation (PRD C-2), so no installment
	 * is written here.
	 *
	 * @param principal         financing amount after the down payment; computed by the caller, never
	 *                          accepted from the client (DM §1.3 invariant)
	 * @param plannedStartDate  date the operator planned while drafting; required by the create API
	 * @param idempotencyKey    endpoint-scoped retry key of the creating request (TS §2.5)
	 */
	public Contract(String contractNo, Customer customer, Asset asset, BigDecimal assetPrice, BigDecimal principal,
			BigDecimal downPayment, int tenorMonths, InterestScheme interestScheme, BigDecimal interestRate,
			int gracePeriodDays, BigDecimal penaltyRateDaily, LocalDate plannedStartDate, String idempotencyKey) {
		this.contractNo = contractNo;
		this.customer = customer;
		this.asset = asset;
		this.assetPrice = assetPrice;
		this.principal = principal;
		this.downPayment = downPayment;
		this.tenorMonths = tenorMonths;
		this.interestScheme = interestScheme;
		this.interestRate = interestRate;
		this.gracePeriodDays = gracePeriodDays;
		this.penaltyRateDaily = penaltyRateDaily;
		this.plannedStartDate = plannedStartDate;
		this.idempotencyKey = idempotencyKey;
		this.status = ContractStatus.DRAFT;
	}

	/**
	 * Activates a DRAFT contract: pins the effective start date and re-snapshots the configuration
	 * that governs it (DM §1.3, PRD C-2). Writes the status transition and the snapshot in this
	 * single row update so the V4 coherence CHECK always sees a coherent row.
	 *
	 * @param effectiveStartDate the activation date that the schedule is derived from; must not be
	 *                           {@code null} — the caller resolves the request override or falls back
	 *                           to {@link #getPlannedStartDate()}
	 * @throws ContractStateException if the contract is not DRAFT (activation may not run twice) or
	 *                               when no effective start date could be resolved
	 * @throws IllegalArgumentException if the configuration snapshot is negative
	 */
	public void activate(LocalDate effectiveStartDate, int gracePeriodDays, BigDecimal penaltyRateDaily) {
		if (status != ContractStatus.DRAFT) {
			throw new ContractStateException(
					"contract " + contractNo + " is " + status + " and can no longer be activated");
		}
		if (effectiveStartDate == null) {
			throw new ContractStateException("contract " + contractNo
					+ " has no effective start date; supply start_date or a planned start date");
		}
		if (gracePeriodDays < 0) {
			throw new IllegalArgumentException("gracePeriodDays must be >= 0 but was " + gracePeriodDays);
		}
		if (penaltyRateDaily == null || penaltyRateDaily.signum() < 0) {
			throw new IllegalArgumentException("penaltyRateDaily must be >= 0 but was " + penaltyRateDaily);
		}
		this.startDate = effectiveStartDate;
		this.gracePeriodDays = gracePeriodDays;
		this.penaltyRateDaily = penaltyRateDaily;
		this.status = ContractStatus.ACTIVE;
	}

	/** DRAFT: authored intent, no schedule, no effective start date. */
	public boolean isDraft() {
		return status == ContractStatus.DRAFT;
	}

	/** ACTIVE: the schedule is pinned and the contract is being serviced. */
	public boolean isActive() {
		return status == ContractStatus.ACTIVE;
	}

	public UUID getId() {
		return id;
	}

	public String getContractNo() {
		return contractNo;
	}

	public Customer getCustomer() {
		return customer;
	}

	public Asset getAsset() {
		return asset;
	}

	public BigDecimal getAssetPrice() {
		return assetPrice;
	}

	public BigDecimal getPrincipal() {
		return principal;
	}

	public BigDecimal getDownPayment() {
		return downPayment;
	}

	public int getTenorMonths() {
		return tenorMonths;
	}

	public InterestScheme getInterestScheme() {
		return interestScheme;
	}

	public BigDecimal getInterestRate() {
		return interestRate;
	}

	public int getGracePeriodDays() {
		return gracePeriodDays;
	}

	public BigDecimal getPenaltyRateDaily() {
		return penaltyRateDaily;
	}

	public LocalDate getPlannedStartDate() {
		return plannedStartDate;
	}

	public LocalDate getStartDate() {
		return startDate;
	}

	public String getIdempotencyKey() {
		return idempotencyKey;
	}

	public ContractStatus getStatus() {
		return status;
	}

	public String getWriteOffReason() {
		return writeOffReason;
	}

	public UUID getWriteOffRecordedBy() {
		return writeOffRecordedBy;
	}

	public OffsetDateTime getClosedAt() {
		return closedAt;
	}

	public ClosedReason getClosedReason() {
		return closedReason;
	}

	public long getVersion() {
		return version;
	}
}