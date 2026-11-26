package com.serfira.contract.application;

import java.time.LocalDate;
import java.util.List;

/**
 * The {@code contract} module's read seam for the collection-rate cohort of the outstanding report (T36,
 * ADR-026 D5/D8). The {@code reporting} module never reads {@code installment} rows itself; it asks this port
 * for the installments due in the period and their receivable, and asks the {@code payment} module for what
 * was collected on them.
 */
public interface CollectionCohortSourcePort {

	/**
	 * Every installment whose {@code due_date} is in {@code [from, to]} (both inclusive) on a contract that is
	 * not DRAFT (ACTIVE, CLOSED or TERMINATED), so a contract that matured, settled or was written off during
	 * the period still counts (ADR-026 D1). Installments with nothing recognized are included with zero.
	 *
	 * <p>Read-only, in its own read-only transaction. The order is deterministic (contract number, then
	 * period).
	 *
	 * @param from first due date of the cohort, inclusive
	 * @param to   last due date of the cohort, inclusive; not before {@code from}
	 * @return the cohort, never {@code null}; empty when nothing falls due in the range
	 */
	List<CohortReceivableSnapshot> findCohort(LocalDate from, LocalDate to);
}
