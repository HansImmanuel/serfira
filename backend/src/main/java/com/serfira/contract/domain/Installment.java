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
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * One period obligation of a contract schedule (03_DOMAIN_MODEL.md §1.4).
 *
 * <p>{@code principalAmount} + {@code interestAmount} come from the generated schedule; the resolution
 * fields ({@code recognizedInterestAmount}/{@code penaltyAmount} + paid/settled/written-off amounts) are
 * mutated by billing/penalty/payment/settlement only (stories C/D/E); the daily aging step (T6) changes the
 * status only, never an amount ({@link #markOverdue}). One schedule per contract is
 * enforced by the unique {@code (contract_id, period_no)} constraint.
 */
@Entity
@Table(name = "installment", uniqueConstraints = {
		@UniqueConstraint(name = "uk_installment_period", columnNames = {"contract_id", "period_no"})
})
public class Installment extends Auditable {

	/** Money is scale-2 ({@code NUMERIC(19,2)}); resolution amounts are never rounded. */
	private static final int MONEY_SCALE = 2;

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

	/**
	 * Applies a resolved payment amount to this installment (DM §1.4, PRD P-3): raises
	 * {@code paid_amount}, stamps {@code paid_at} the first time the installment is resolved, and
	 * derives {@code PARTIALLY_PAID} / {@code PAID} from the receivable definition
	 * ({@link InstallmentBalance}).
	 *
	 * <p>Called by the allocation write path (payment C3) with the engine's output only; the amount is
	 * therefore already capped by {@code recognized_total − resolved_amount} (ADR-009, V3 trigger).
	 * Resolution that is <b>not</b> a payment keeps its own state: this method never downgrades
	 * {@code SETTLED} / {@code WRITTEN_OFF}. A never-paid installment that receives a partial payment
	 * leaves the {@code OVERDUE} aging state for {@code PARTIALLY_PAID} (PRD P-3); the aging job
	 * re-marks it if it is still overdue.
	 *
	 * <p>This derivation ignores penalty adjustments (it reads {@link InstallmentBalance#of(Installment)}),
	 * which is correct for a payment: a payment never changes the recognized penalty. The waiver flow (E5)
	 * re-derives the status from the adjustment-aware balance through {@link #recomputeResolutionStatus},
	 * so a waived penalty does not keep an otherwise-resolved installment in {@code PARTIALLY_PAID}.
	 *
	 * @param amount scale-2 money, {@code > 0}
	 * @param paidAt business instant of the payment; kept from the first resolving payment
	 * @throws IllegalArgumentException if the amount is not positive scale-2 money
	 * @throws ArithmeticException      if the amount is finer than scale 2
	 */
	public void applyPayment(BigDecimal amount, OffsetDateTime paidAt) {
		Objects.requireNonNull(paidAt, "paidAt");
		if (amount == null) {
			throw new IllegalArgumentException("amount is required");
		}
		BigDecimal resolution = amount.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
		if (resolution.signum() <= 0) {
			throw new IllegalArgumentException("a payment resolution must be > 0 but was " + resolution);
		}
		this.paidAmount = this.paidAmount.add(resolution);
		if (this.paidAt == null) {
			this.paidAt = paidAt;
		}
		if (status != InstallmentStatus.SETTLED && status != InstallmentStatus.WRITTEN_OFF) {
			this.status = InstallmentBalance.of(this).outstanding().signum() == 0
					? InstallmentStatus.PAID
					: InstallmentStatus.PARTIALLY_PAID;
		}
	}

	/**
	 * Re-derives the resolution status after a penalty waiver/reduce (E5, task T15, ADR-019 D3): a waiver
	 * lowers the recognized penalty but never touches the installment's amounts, so without this the status
	 * stays {@code OVERDUE}/{@code PARTIALLY_PAID} even when the write-down clears the last amount owed,
	 * which would block the maturity close (DM §3 invariant 17).
	 *
	 * <p>The status is derived from the adjustment-aware balance ({@link InstallmentBalance#of(Installment,
	 * BigDecimal)}): {@code outstanding == 0} means {@code PAID}, otherwise {@code PARTIALLY_PAID}. The same
	 * guards as {@link #applyPayment} apply — {@code SETTLED}/{@code WRITTEN_OFF} are terminal and never
	 * downgraded, and a {@code PENDING} installment with nothing resolved is left as it is rather than being
	 * moved to {@code PARTIALLY_PAID}. Unlike {@link #applyPayment} this method deliberately does <b>not</b>
	 * stamp {@code paid_at}: a waiver is not cash received, so re-stamping would misrecord when the customer
	 * paid (invariant: {@code paid_at} is the first resolving payment's instant).
	 *
	 * <p>Returns {@code true} when the status field actually changed, which dirties the row and bumps
	 * {@code @Version} on flush. That versioned write is what serializes concurrent waivers on the same
	 * installment (F4): two racing waivers collide on {@code @Version} so exactly one commits.
	 *
	 * @param penaltyAdjustments Σ active penalty adjustment for this installment, scale-2, {@code >= 0}
	 * @return {@code true} if the status changed
	 */
	public boolean recomputeResolutionStatus(BigDecimal penaltyAdjustments) {
		Objects.requireNonNull(penaltyAdjustments, "penaltyAdjustments");
		if (status == InstallmentStatus.SETTLED || status == InstallmentStatus.WRITTEN_OFF) {
			return false;
		}
		boolean resolved = InstallmentBalance.of(this, penaltyAdjustments).outstanding().signum() == 0;
		InstallmentStatus recomputed;
		if (resolved) {
			recomputed = InstallmentStatus.PAID;
		} else if (status == InstallmentStatus.PENDING && paidAmount.signum() == 0) {
			// Nothing has resolved against a never-touched installment: a partial waiver that leaves it
			// outstanding keeps it PENDING; promoting to PARTIALLY_PAID would misreport that money arrived.
			recomputed = InstallmentStatus.PENDING;
		} else {
			recomputed = InstallmentStatus.PARTIALLY_PAID;
		}
		if (recomputed == status) {
			return false;
		}
		this.status = recomputed;
		return true;
	}

	/**
	 * Recognizes scheduled interest of this installment as receivable (DM §1.4, 04_GAPS_ADDENDUM.md §12):
	 * raises {@code recognized_interest_amount} by {@code amount}, the unbilled remainder of
	 * {@code interest_amount} computed by the billing step (story C4).
	 *
	 * <p>Recognition is an accounting fact about the schedule, not money received, so the resolution
	 * state is deliberately left alone: an installment that an earlier principal-only payment already
	 * resolved keeps {@code PAID} while its interest becomes receivable, and the customer's overpayment
	 * stays a {@code TITIPAN_NASABAH} liability for E3 to apply (ADR-011). {@code SETTLED} and
	 * {@code WRITTEN_OFF} installments can never be billed — settlement recognizes its own accrued
	 * interest (Addendum §12) and a write-off caps the recognized receivable (invariant 15) — so that
	 * guard is defensive; the billing step skips those states before calling here.
	 *
	 * @param amount scale-2 money, {@code > 0}, at most the unbilled remainder of {@code interestAmount}
	 * @throws ContractStateException   if the installment is already {@code SETTLED} / {@code WRITTEN_OFF}
	 * @throws IllegalArgumentException if the amount is not positive, or recognition would pass the
	 *                                  scheduled interest (invariant 10)
	 * @throws ArithmeticException      if the amount is finer than scale 2
	 */
	public void recognizeInterest(BigDecimal amount) {
		if (amount == null) {
			throw new IllegalArgumentException("amount is required");
		}
		BigDecimal recognized = amount.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
		if (recognized.signum() <= 0) {
			throw new IllegalArgumentException("a recognized interest amount must be > 0 but was " + recognized);
		}
		if (status == InstallmentStatus.SETTLED || status == InstallmentStatus.WRITTEN_OFF) {
			throw new ContractStateException(
					"installment " + periodNo + " is " + status + " and can no longer be billed");
		}
		BigDecimal total = this.recognizedInterestAmount.add(recognized);
		if (total.compareTo(interestAmount) > 0) {
			throw new IllegalArgumentException("recognized interest " + total + " of installment " + periodNo
					+ " would exceed the scheduled interest " + interestAmount + " (invariant 10)");
		}
		this.recognizedInterestAmount = total;
	}

	/**
	 * Recognizes one day's late-payment penalty of this installment as receivable (DM §1.9, PRD D-1,
	 * TS §4.3): raises {@code penalty_amount} by {@code amount}.
	 *
	 * <p>Called by the daily penalty step (D1) with one day's charge, after the grace period of the
	 * installment's due date has passed; the caller decides <b>which</b> days are still owed, this method only
	 * records the amount (ADR-012). Because {@code penalty_amount} is the <b>gross</b> recognized penalty, the
	 * sum of an installment's {@code penalty_accrual} rows equals the value written here — payment and waiver
	 * never change it; they reduce the effective outstanding penalty instead (invariant 9).
	 *
	 * <p>The resolution state is deliberately left alone: a penalty is recognized against a late day even if
	 * the installment has since been paid, and the aging state ({@code OVERDUE}) stays the aging job's decision
	 * (story D2). {@code SETTLED}/{@code WRITTEN_OFF} installments can never accrue more: settlement settles the
	 * denda outstanding and a write-off caps the recognized receivable (invariant 15), so that guard is
	 * defensive — the penalty step skips those states before calling here.
	 *
	 * @param amount scale-2 money, {@code > 0}
	 * @throws ContractStateException   if the installment is already {@code SETTLED} / {@code WRITTEN_OFF}
	 * @throws IllegalArgumentException if the amount is not positive
	 * @throws ArithmeticException      if the amount is finer than scale 2
	 */
	public void accruePenalty(BigDecimal amount) {
		if (amount == null) {
			throw new IllegalArgumentException("amount is required");
		}
		BigDecimal accrued = amount.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
		if (accrued.signum() <= 0) {
			throw new IllegalArgumentException("an accrued penalty amount must be > 0 but was " + accrued);
		}
		if (status == InstallmentStatus.SETTLED || status == InstallmentStatus.WRITTEN_OFF) {
			throw new ContractStateException(
					"installment " + periodNo + " is " + status + " and can no longer accrue a penalty");
		}
		this.penaltyAmount = this.penaltyAmount.add(accrued);
	}

	/**
	 * Applies the aging transition of the daily job (DM §1.4, ADR-013 A-4/A-5): a {@code PENDING} or
	 * {@code PARTIALLY_PAID} installment with {@code outstanding > 0} becomes {@code OVERDUE} from
	 * {@link InstallmentAging#firstOverdueDate} onwards.
	 *
	 * <p>Every other case is a no-op that touches no field, so the row is not dirty and its version does not
	 * move: an installment that is already {@code OVERDUE} is never rewritten, and {@code PAID},
	 * {@code SETTLED} and {@code WRITTEN_OFF} are never changed by aging. Aging only ever moves an installment
	 * <b>into</b> {@code OVERDUE}; leaving it is the payment path's decision ({@link #applyPayment}).
	 *
	 * <p>Like {@link #applyPayment}, the outstanding check reads the zero-adjustment balance
	 * ({@link InstallmentBalance#of(Installment)}). A waiver that clears the only remaining amount runs
	 * {@link #recomputeResolutionStatus} and flips the installment to {@code PAID} first, so aging then
	 * finds a terminal status and leaves it alone — an installment whose only remainder is a waived penalty
	 * is never aged.
	 *
	 * @param businessDate    business date the aging step runs for
	 * @param gracePeriodDays the contract's activation-snapshot grace period, {@code >= 0}
	 * @return {@code true} when the status changed to {@code OVERDUE}, {@code false} when nothing changed
	 */
	public boolean markOverdue(LocalDate businessDate, int gracePeriodDays) {
		Objects.requireNonNull(businessDate, "businessDate");
		LocalDate firstOverdueDate = InstallmentAging.firstOverdueDate(dueDate, gracePeriodDays);
		if (status != InstallmentStatus.PENDING && status != InstallmentStatus.PARTIALLY_PAID) {
			return false;
		}
		if (businessDate.isBefore(firstOverdueDate)) {
			return false;
		}
		if (InstallmentBalance.of(this).outstanding().signum() <= 0) {
			return false;
		}
		this.status = InstallmentStatus.OVERDUE;
		return true;
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