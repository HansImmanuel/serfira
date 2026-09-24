package com.serfira.penalty.domain;

import com.serfira.shared.audit.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * One day of recognized late-payment penalty for one installment (03_DOMAIN_MODEL.md §1.9, story D1):
 * the incremental daily accrual that makes denda a receivable ({@code PIUTANG_DENDA} /
 * {@code PENDAPATAN_DENDA}, Addendum §12).
 *
 * <p><b>Append-only.</b> A row records one day's charge and is never corrected afterwards — neither by the
 * step that wrote it (a day is skipped once it has a row) nor by a later recalculation (Addendum §6: "recalc
 * tidak mengubah histori accrual"). A reduction is a {@code penalty_adjustment} (E5), which is append-only
 * as well and carries its own journal. The unique {@code (installment_id, accrual_date)} constraint is what
 * makes a repeated run free: the row's existence <i>is</i> the idempotence guard (invariant 8).
 *
 * <p>{@code installmentId} is kept as a plain id, not an entity association: {@code installment} belongs to
 * the {@code contract} module (ADR-012), so the two modules only meet through
 * {@code InstallmentPenaltyPort}. The foreign key in V1 protects the reference.
 *
 * <p>{@code amount} is that day's delta, never a cumulative snapshot, so summing an installment's rows gives
 * the gross {@code penalty_amount} without double counting (TS §4.3, DM §1.9).
 */
@Entity
@Table(name = "penalty_accrual", uniqueConstraints = {
		@UniqueConstraint(name = "uk_penalty_accrual", columnNames = {"installment_id", "accrual_date"})
})
public class PenaltyAccrual extends Auditable {

	/** Money is scale-2 ({@code NUMERIC(19,2)}); the amount is never rounded here (TS §2.1). */
	private static final int MONEY_SCALE = 2;

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "installment_id", nullable = false, updatable = false)
	private UUID installmentId;

	@Column(name = "accrual_date", nullable = false, updatable = false)
	private LocalDate accrualDate;

	@Column(name = "days_late", nullable = false, updatable = false)
	private int daysLate;

	@Column(name = "amount", nullable = false, updatable = false, precision = 19, scale = 2)
	private BigDecimal amount;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected PenaltyAccrual() {
		// JPA
	}

	/**
	 * Creates one day's accrual row with the invariants V1 also asserts
	 * ({@code ck_penalty_accrual_amount}, {@code ck_penalty_accrual_days}).
	 *
	 * @param installmentId installment the charge belongs to
	 * @param accrualDate   business date of the charge
	 * @param daysLate      {@code hariTelat} of that date, {@code >= 1} (TS §4.3)
	 * @param amount        that day's penalty, scale-2 money, {@code > 0}
	 * @throws IllegalArgumentException when a value could never be chargeable, so a caller that passes one
	 *                                  has a defect rather than a rounding difference
	 */
	public PenaltyAccrual(UUID installmentId, LocalDate accrualDate, int daysLate, BigDecimal amount) {
		Objects.requireNonNull(installmentId, "installmentId");
		Objects.requireNonNull(accrualDate, "accrualDate");
		Objects.requireNonNull(amount, "amount");
		if (daysLate < 1) {
			throw new IllegalArgumentException("a penalty accrual exists only for a late day but daysLate was "
					+ daysLate);
		}
		try {
			amount = amount.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
		} catch (ArithmeticException ex) {
			throw new IllegalArgumentException("an accrued penalty must be money with at most " + MONEY_SCALE
					+ " decimal places but was " + amount);
		}
		if (amount.signum() <= 0) {
			throw new IllegalArgumentException("an accrued penalty must be > 0 but was " + amount);
		}
		this.installmentId = installmentId;
		this.accrualDate = accrualDate;
		this.daysLate = daysLate;
		this.amount = amount;
	}

	public UUID getId() {
		return id;
	}

	public UUID getInstallmentId() {
		return installmentId;
	}

	public LocalDate getAccrualDate() {
		return accrualDate;
	}

	public int getDaysLate() {
		return daysLate;
	}

	public BigDecimal getAmount() {
		return amount;
	}

	public long getVersion() {
		return version;
	}
}
