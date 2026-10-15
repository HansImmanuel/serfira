package com.serfira.penalty.domain.consistency;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * The reconciliation figures for one contract, gathered from the owning modules' read ports and handed to
 * the pure {@link ConsistencyCheckEngine} (F1, task T17, Addendum §7.1). Keeping the figures in one value
 * object lets the three checks be golden-tested without Spring or a database.
 *
 * <p>Each field is the already-computed side of one check; the engine only compares them. All amounts are
 * scale-2 money (sign preserved for the ledger-derived figures).
 *
 * @param postedPaymentTotal    {@code Σ} POSTED payment amount (payment module) — check A/B expected
 * @param postedAllocationTotal {@code Σ} POSTED payment allocation amount (payment module) — check B actual
 * @param ledgerPaymentCash     net {@code KAS} from {@code PAYMENT} entries (ledger) — check A actual
 * @param receivableResidual    {@code Σ} installment receivable residual (contract) — check C expected
 * @param ledgerReceivable      net {@code PIUTANG_*} balance (ledger) — check C actual
 */
public record ContractReconciliationInput(BigDecimal postedPaymentTotal, BigDecimal postedAllocationTotal,
		BigDecimal ledgerPaymentCash, BigDecimal receivableResidual, BigDecimal ledgerReceivable) {

	public ContractReconciliationInput {
		Objects.requireNonNull(postedPaymentTotal, "postedPaymentTotal");
		Objects.requireNonNull(postedAllocationTotal, "postedAllocationTotal");
		Objects.requireNonNull(ledgerPaymentCash, "ledgerPaymentCash");
		Objects.requireNonNull(receivableResidual, "receivableResidual");
		Objects.requireNonNull(ledgerReceivable, "ledgerReceivable");
	}
}
