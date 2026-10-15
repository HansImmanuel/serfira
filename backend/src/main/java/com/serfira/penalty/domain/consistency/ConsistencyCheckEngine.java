package com.serfira.penalty.domain.consistency;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Pure reconciliation of one contract's three independent money checks (F1 / story L-3, Addendum §7.1).
 * No Spring, no database: it takes the already-gathered figures ({@link ContractReconciliationInput}) and
 * returns the drifts to record, so the three checks are golden-testable in isolation (same convention as
 * {@code PenaltyCalculator}, {@code PaymentAllocationEngine}).
 *
 * <p>The three checks (Addendum §7.1):
 * <ol>
 *   <li><b>A — PAYMENT_CASH:</b> {@code Σ POSTED Payment.amount == net KAS received via PAYMENT entries}.</li>
 *   <li><b>B — ALLOCATION_PAYMENT:</b> {@code Σ PaymentAllocation.amount == Σ Payment.amount} (invariant 6).</li>
 *   <li><b>C — RECEIVABLE_INSTALLMENT:</b> {@code Σ installment receivable residual == net PIUTANG_* balance}.</li>
 * </ol>
 *
 * <p>Comparison is exact on scale-2 money, by {@link BigDecimal#compareTo} so {@code 0} and {@code 0.00}
 * are equal: every figure is a {@code NUMERIC(19,2)} aggregate, so there is no rounding slack to tolerate —
 * any non-zero difference is real drift (there is deliberately no epsilon; a tolerance would hide the
 * sub-rupiah corruption this check exists to catch). "Report, do not fix": the engine only describes drift;
 * writing the {@code reconciliation_exception} rows and never mutating money is the service's job
 * (Addendum §7.3).
 */
public final class ConsistencyCheckEngine {

	private ConsistencyCheckEngine() {
	}

	/**
	 * Runs all three checks for one contract and returns one {@link ConsistencyDiscrepancy} per check that
	 * failed, in check order (A, B, C). An empty list means the contract reconciles.
	 *
	 * @param input the contract's gathered reconciliation figures; must not be null
	 * @return the drifts to record; empty when every check balances
	 */
	public static List<ConsistencyDiscrepancy> check(ContractReconciliationInput input) {
		Objects.requireNonNull(input, "input");
		List<ConsistencyDiscrepancy> discrepancies = new ArrayList<>(3);
		// A — POSTED payment total vs. net KAS received through PAYMENT entries.
		add(discrepancies, ConsistencyCheckType.PAYMENT_CASH,
				input.postedPaymentTotal(), input.ledgerPaymentCash());
		// B — POSTED payment total vs. its allocation total (invariant 6).
		add(discrepancies, ConsistencyCheckType.ALLOCATION_PAYMENT,
				input.postedPaymentTotal(), input.postedAllocationTotal());
		// C — installment receivable residual vs. net PIUTANG_* ledger balance.
		add(discrepancies, ConsistencyCheckType.RECEIVABLE_INSTALLMENT,
				input.receivableResidual(), input.ledgerReceivable());
		return List.copyOf(discrepancies);
	}

	private static void add(List<ConsistencyDiscrepancy> discrepancies, ConsistencyCheckType type,
			BigDecimal expected, BigDecimal actual) {
		if (expected.compareTo(actual) != 0) {
			discrepancies.add(new ConsistencyDiscrepancy(type, expected, actual, expected.subtract(actual)));
		}
	}
}
