package com.serfira.contract.application;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * One ACTIVE-contract installment that still owes money, as the aging report reads it (ADR-013 A-4/A-7,
 * 04_GAPS_ADDENDUM.md §14). The {@code contract} module owns both the days-past-due calendar
 * ({@link com.serfira.contract.domain.InstallmentAging}) and the outstanding formula
 * ({@link com.serfira.contract.domain.InstallmentBalance}), so the report consumes already-computed values
 * and never restates either formula (02_TECH_SPEC.md §1: {@code reporting} reads, it does not re-derive).
 *
 * <p>Only installments with {@code outstanding > 0} are emitted; a {@code SETTLED}, {@code WRITTEN_OFF} or
 * fully {@code PAID} installment has zero outstanding and simply never appears. Days past due is the aging
 * DPD on the business date the snapshot was taken: {@code 0} for a not-yet-due or within-grace installment,
 * then 1, 2, …
 *
 * @param contractId   owning contract
 * @param contractNo   owning contract's business number ({@code MF-YYYYMM-XXXX}), for the per-contract row
 * @param daysPastDue  aging DPD on the snapshot's business date, {@code >= 0}
 * @param outstanding  installment outstanding (principal + recognized interest + penalty − resolved),
 *                     scale-2, {@code > 0}
 */
public record InstallmentAgingSnapshot(UUID contractId, String contractNo, int daysPastDue, BigDecimal outstanding) {

	public InstallmentAgingSnapshot {
		Objects.requireNonNull(contractId, "contractId");
		Objects.requireNonNull(contractNo, "contractNo");
		Objects.requireNonNull(outstanding, "outstanding");
		if (daysPastDue < 0) {
			throw new IllegalArgumentException("daysPastDue must be >= 0 but was " + daysPastDue);
		}
		if (outstanding.signum() <= 0) {
			throw new IllegalArgumentException("an aging snapshot only exists for outstanding > 0 but was "
					+ outstanding);
		}
	}
}
