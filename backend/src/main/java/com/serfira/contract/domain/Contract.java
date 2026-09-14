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
 * <p>{@code gracePeriodDays} and {@code penaltyRateDaily} are snapshots of the global configuration taken
 * at activation (Addendum §1.1), never live references — configuration changes must not alter running
 * contracts retroactively. {@code version} carries the optimistic lock for concurrent writes.
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

	@Column(name = "start_date")
	private LocalDate startDate;

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

	public Contract(String contractNo, Customer customer, Asset asset, BigDecimal assetPrice, BigDecimal principal,
			BigDecimal downPayment, int tenorMonths, InterestScheme interestScheme, BigDecimal interestRate,
			int gracePeriodDays, BigDecimal penaltyRateDaily) {
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
		this.status = ContractStatus.DRAFT;
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

	public LocalDate getStartDate() {
		return startDate;
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