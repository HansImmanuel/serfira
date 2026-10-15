package com.serfira.penalty.domain.consistency;

import com.serfira.shared.audit.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * A reconciliation drift the daily consistency check recorded for one contract (F1 / story L-3,
 * Addendum §7.1/§7.2, V1 {@code reconciliation_exception}). The job only ever <b>inserts</b> these: the
 * financial system never auto-corrects, it records the exception and leaves resolution to a human
 * (Addendum §7.3). Marking a row {@code INVESTIGATING}/{@code RESOLVED} is a future operator endpoint
 * ({@code POST /reports/reconciliation-exceptions/{id}/resolve}, deferred), not this job.
 *
 * <p>Owned by the {@code penalty} module because the consistency step (hosted in the daily-job host) is the
 * sole writer; the planned Finance read endpoint will read it through a {@code reporting} seam. The guards
 * mirror the V1 {@code ck_recon_exception_type}/{@code ck_recon_exception_status} CHECK constraints so an
 * invalid row cannot be built in memory and then rejected at COMMIT.
 */
@Entity
@Table(name = "reconciliation_exception")
public class ReconciliationException extends Auditable {

	private static final int MONEY_SCALE = 2;

	/**
	 * The {@code status} every row is created with: an unresolved drift (V1 {@code ck_recon_exception_status}).
	 * A human moves it to {@code INVESTIGATING}/{@code RESOLVED} later (Addendum §7.3). Exposed so the
	 * consistency step can query for an existing OPEN row without duplicating the literal.
	 */
	public static final String OPEN_STATUS = "OPEN";

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "contract_id", nullable = false, updatable = false)
	private UUID contractId;

	@Column(name = "check_date", nullable = false, updatable = false)
	private LocalDate checkDate;

	@Column(name = "check_type", nullable = false, updatable = false, length = 40)
	private String checkType;

	@Column(name = "expected_amount", nullable = false, updatable = false, precision = 19, scale = 2)
	private BigDecimal expectedAmount;

	@Column(name = "actual_amount", nullable = false, updatable = false, precision = 19, scale = 2)
	private BigDecimal actualAmount;

	@Column(name = "diff_amount", nullable = false, updatable = false, precision = 19, scale = 2)
	private BigDecimal diffAmount;

	@Column(name = "status", nullable = false, length = 20)
	private String status;

	protected ReconciliationException() {
		// JPA
	}

	/**
	 * Records a drift found by the consistency check as a new {@code OPEN} exception.
	 *
	 * @param contractId contract the drift belongs to
	 * @param checkDate  the business date the check ran for
	 * @param discrepancy the failed check (type and the three amounts)
	 */
	public ReconciliationException(UUID contractId, LocalDate checkDate, ConsistencyDiscrepancy discrepancy) {
		this.contractId = Objects.requireNonNull(contractId, "contractId");
		this.checkDate = Objects.requireNonNull(checkDate, "checkDate");
		Objects.requireNonNull(discrepancy, "discrepancy");
		this.checkType = discrepancy.type().dbValue();
		this.expectedAmount = money(discrepancy.expected(), "expected");
		this.actualAmount = money(discrepancy.actual(), "actual");
		this.diffAmount = money(discrepancy.diff(), "diff");
		this.status = OPEN_STATUS;
	}

	public UUID getId() {
		return id;
	}

	public UUID getContractId() {
		return contractId;
	}

	public LocalDate getCheckDate() {
		return checkDate;
	}

	public String getCheckType() {
		return checkType;
	}

	public BigDecimal getExpectedAmount() {
		return expectedAmount;
	}

	public BigDecimal getActualAmount() {
		return actualAmount;
	}

	public BigDecimal getDiffAmount() {
		return diffAmount;
	}

	public String getStatus() {
		return status;
	}

	private static BigDecimal money(BigDecimal value, String name) {
		Objects.requireNonNull(value, name);
		try {
			return value.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
		} catch (ArithmeticException ex) {
			throw new IllegalArgumentException(name + " must be money with at most " + MONEY_SCALE
					+ " decimal places but was " + value, ex);
		}
	}
}
