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
 * One period obligation of a contract schedule (03_DOMAIN_MODEL.md §1.4).
 *
 * <p>{@code principalAmount} + {@code interestAmount} come from the generated schedule; the resolution
 * fields ({@code recognizedInterestAmount}/{@code penaltyAmount} + paid/settled/written-off amounts) are
 * mutated by billing/penalty/payment/settlement only (stories C/D/E). One schedule per contract is
 * enforced by the unique {@code (contract_id, period_no)} constraint.
 */
@Entity
@Table(name = "installment", uniqueConstraints = {
		@UniqueConstraint(name = "uk_installment_period", columnNames = {"contract_id", "period_no"})
})
public class Installment extends Auditable {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "contract_id", nullable = false)
	private Contract contract;

	@Column(name = "period_no", nullable = false)
	private int periodNo;

	@Column(name = "due_date", nullable = false)
	private LocalDate dueDate;

	@Column(name = "principal_amount", nullable = false, precision = 19, scale = 2)
	private BigDecimal principalAmount;

	@Column(name = "interest_amount", nullable = false, precision = 19, scale = 2)
	private BigDecimal interestAmount;

	@Column(name = "recognized_interest_amount", nullable = false, precision = 19, scale = 2)
	private BigDecimal recognizedInterestAmount = BigDecimal.ZERO;

	@Column(name = "penalty_amount", nullable = false, precision = 19, scale = 2)
	private BigDecimal penaltyAmount = BigDecimal.ZERO;

	@Column(name = "paid_amount", nullable = false, precision = 19, scale = 2)
	private BigDecimal paidAmount = BigDecimal.ZERO;

	@Column(name = "settled_amount", nullable = false, precision = 19, scale = 2)
	private BigDecimal settledAmount = BigDecimal.ZERO;

	@Column(name = "written_off_amount", nullable = false, precision = 19, scale = 2)
	private BigDecimal writtenOffAmount = BigDecimal.ZERO;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private InstallmentStatus status = InstallmentStatus.PENDING;

	@Column(name = "paid_at", columnDefinition = "timestamptz")
	private OffsetDateTime paidAt;

	@Column(name = "settled_at", columnDefinition = "timestamptz")
	private OffsetDateTime settledAt;

	@Column(name = "written_off_at", columnDefinition = "timestamptz")
	private OffsetDateTime writtenOffAt;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected Installment() {
		// JPA
	}

	public Installment(Contract contract, int periodNo, LocalDate dueDate, BigDecimal principalAmount,
			BigDecimal interestAmount) {
		this.contract = contract;
		this.periodNo = periodNo;
		this.dueDate = dueDate;
		this.principalAmount = principalAmount;
		this.interestAmount = interestAmount;
	}

	public UUID getId() {
		return id;
	}

	public Contract getContract() {
		return contract;
	}

	public int getPeriodNo() {
		return periodNo;
	}

	public LocalDate getDueDate() {
		return dueDate;
	}

	public BigDecimal getPrincipalAmount() {
		return principalAmount;
	}

	public BigDecimal getInterestAmount() {
		return interestAmount;
	}

	public BigDecimal getRecognizedInterestAmount() {
		return recognizedInterestAmount;
	}

	public BigDecimal getPenaltyAmount() {
		return penaltyAmount;
	}

	public BigDecimal getPaidAmount() {
		return paidAmount;
	}

	public BigDecimal getSettledAmount() {
		return settledAmount;
	}

	public BigDecimal getWrittenOffAmount() {
		return writtenOffAmount;
	}

	public InstallmentStatus getStatus() {
		return status;
	}

	public OffsetDateTime getPaidAt() {
		return paidAt;
	}

	public OffsetDateTime getSettledAt() {
		return settledAt;
	}

	public OffsetDateTime getWrittenOffAt() {
		return writtenOffAt;
	}

	public long getVersion() {
		return version;
	}
}