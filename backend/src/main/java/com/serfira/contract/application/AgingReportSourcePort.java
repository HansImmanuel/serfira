package com.serfira.contract.application;

import java.time.LocalDate;
import java.util.List;

/**
 * The {@code contract} module's read seam for the aging report (T8, PRD D-2, ADR-013 A-6/A-7): every
 * owed installment of every ACTIVE contract on a business date, each already carrying its days-past-due and
 * its outstanding. The {@code reporting} module buckets these rows; it never touches contract entities,
 * repositories or tables (02_TECH_SPEC.md §1 module dependency rules).
 *
 * <p>Why a port rather than {@code reporting} reading installments directly: the DPD calendar
 * ({@link com.serfira.contract.domain.InstallmentAging}) and the outstanding formula
 * ({@link com.serfira.contract.domain.InstallmentBalance}) are domain rules owned here. Exposing them once,
 * as snapshots, keeps a single definition of "late" and "outstanding" across billing, aging and reporting
 * (the T8 scope note: do not introduce a second formula).
 */
public interface AgingReportSourcePort {

	/**
	 * Snapshots of every ACTIVE-contract installment with {@code outstanding > 0} on {@code asOf}.
	 *
	 * <p>Scope is ACTIVE contracts only (ADR-013 A-7): DRAFT contracts have no schedule, and
	 * CLOSED/TERMINATED contracts are done. {@code SETTLED}/{@code WRITTEN_OFF}/{@code PAID} installments
	 * fall out naturally because their outstanding is zero. The order is deterministic (by contract number,
	 * then period) so paging in the caller is stable.
	 *
	 * @param asOf business date to evaluate days-past-due against
	 * @return owed installment snapshots, never {@code null}; empty when nothing is outstanding
	 */
	List<InstallmentAgingSnapshot> findOutstandingInstallments(LocalDate asOf);
}
