package com.serfira.contract.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * A contract's penalty-relevant state for one accrual run: the configuration <b>snapshotted at activation</b>
 * (grace period and daily rate, Addendum §1.1) plus every installment's due date, penalty base and status
 * (DM §1.4/§1.9, ADR-012).
 *
 * <p>Produced only for a contract that may still accrue: {@link InstallmentPenaltyPort} throws
 * {@code ContractNotFoundException} (404) for an unknown id and {@code ContractStateException} (409) unless
 * the contract is ACTIVE with a schedule. Across contracts the daily job selects ACTIVE ones (D2), so
 * silently skipping another state would hide a caller bug.
 *
 * @param contractId         contract the installments belong to
 * @param contractNo         business number, for journal descriptions (never PII)
 * @param gracePeriodDays    free days after a due date before a penalty may be charged
 * @param penaltyRateDaily   daily rate as a decimal fraction ({@code 0.1% = 0.0010})
 * @param installments       the full schedule in period order, future installments included: which of them
 *                           are chargeable is the caller's decision
 */
public record InstallmentPenaltySnapshot(UUID contractId, String contractNo, int gracePeriodDays,
		BigDecimal penaltyRateDaily, List<InstallmentPenalty> installments) {
}
