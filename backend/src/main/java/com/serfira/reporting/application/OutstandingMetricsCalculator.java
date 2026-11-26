package com.serfira.reporting.application;

import com.serfira.contract.application.CohortReceivableSnapshot;
import com.serfira.contract.application.InstallmentAgingSnapshot;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Pure calculator for the outstanding report's metrics (T36, ADR-026 D2–D7). It aggregates values the owning
 * modules already computed; it never restates the outstanding, DPD or recognized-total formulas (ADR-026 D8,
 * ADR-013 A-7).
 *
 * <p>The stock metrics (outstanding, AR, NPL) come from one pass over the same snapshots the aging report reads,
 * so the two reports agree by construction: a contract is NPL exactly when its aging row has
 * {@code dpd_over_90 > 0}.
 */
public final class OutstandingMetricsCalculator {

	/** A contract is NPL when one of its owed installments is more than this many days past due (D4). */
	static final int NPL_DAYS_PAST_DUE = 90;

	/** Ratios are fractions at scale 4, HALF_UP (D7): {@code "0.0150"} is 1.5%. */
	static final int RATIO_SCALE = 4;

	private static final int MONEY_SCALE = 2;
	private static final BigDecimal ZERO_MONEY = BigDecimal.ZERO.setScale(MONEY_SCALE);

	private OutstandingMetricsCalculator() {
	}

	/** The collection period of D5: {@code [first day of asOf's month, asOf]}; this is its first day. */
	public static LocalDate periodFrom(LocalDate asOf) {
		return Objects.requireNonNull(asOf, "asOf").withDayOfMonth(1);
	}

	/**
	 * Computes every metric on {@code asOf}.
	 *
	 * @param asOf           the business date
	 * @param owed           every ACTIVE-contract installment with {@code outstanding > 0} on {@code asOf}
	 * @param cohort         every installment due in {@code [periodFrom(asOf), asOf]} on a non-DRAFT contract
	 * @param collected      Σ POSTED non-EXCESS payment allocation on the cohort
	 * @param settlementCash Σ {@code cash_received} of settlements executed in the period
	 */
	public static OutstandingMetrics calculate(LocalDate asOf, List<InstallmentAgingSnapshot> owed,
			List<CohortReceivableSnapshot> cohort, BigDecimal collected, BigDecimal settlementCash) {
		Objects.requireNonNull(asOf, "asOf");
		Objects.requireNonNull(owed, "owed");
		Objects.requireNonNull(cohort, "cohort");
		Objects.requireNonNull(collected, "collected");
		Objects.requireNonNull(settlementCash, "settlementCash");

		Set<UUID> activeContracts = new HashSet<>();
		Set<UUID> nplContracts = new HashSet<>();
		BigDecimal totalOutstanding = ZERO_MONEY;
		BigDecimal accountsReceivable = ZERO_MONEY;
		for (InstallmentAgingSnapshot installment : owed) {
			activeContracts.add(installment.contractId());
			totalOutstanding = totalOutstanding.add(installment.outstanding());
			if (!installment.dueDate().isAfter(asOf)) {
				accountsReceivable = accountsReceivable.add(installment.outstanding());
			}
			if (installment.daysPastDue() > NPL_DAYS_PAST_DUE) {
				nplContracts.add(installment.contractId());
			}
		}
		// Whole-contract NPL (D4): every owed installment of an NPL contract, including undue principal.
		BigDecimal nplOutstanding = ZERO_MONEY;
		for (InstallmentAgingSnapshot installment : owed) {
			if (nplContracts.contains(installment.contractId())) {
				nplOutstanding = nplOutstanding.add(installment.outstanding());
			}
		}

		BigDecimal due = ZERO_MONEY;
		for (CohortReceivableSnapshot installment : cohort) {
			due = due.add(installment.recognizedTotal()).subtract(installment.settledAmount());
		}

		return new OutstandingMetrics(
				asOf,
				totalOutstanding,
				accountsReceivable,
				nplContracts.size(),
				nplOutstanding,
				ratio(nplOutstanding, totalOutstanding),
				periodFrom(asOf),
				asOf,
				money(collected),
				due,
				ratio(collected, due),
				money(settlementCash),
				activeContracts.size());
	}

	/** {@code numerator / denominator} at scale 4 HALF_UP, or {@code null} for a zero denominator (D7). */
	static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
		if (denominator.signum() == 0) {
			return null;
		}
		return numerator.divide(denominator, RATIO_SCALE, RoundingMode.HALF_UP);
	}

	private static BigDecimal money(BigDecimal value) {
		return value.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
	}
}
