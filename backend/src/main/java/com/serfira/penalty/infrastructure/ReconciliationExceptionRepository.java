package com.serfira.penalty.infrastructure;

import com.serfira.penalty.domain.consistency.ReconciliationException;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Persistence for {@link ReconciliationException}: the daily consistency check only inserts drift rows
 * (F1, task T17). The {@code status} CHECK and the audit columns are enforced by the database (V1), and
 * the one-OPEN-row-per-key invariant by the V19 partial unique index {@code uq_recon_exception_open}.
 */
public interface ReconciliationExceptionRepository extends JpaRepository<ReconciliationException, UUID> {

	/**
	 * Whether an {@code OPEN} drift is already recorded for this check on this contract and date. Used by
	 * {@link com.serfira.penalty.application.ConsistencyCheckService} to make a same-date re-run idempotent:
	 * a still-drifting contract must not accrue a second OPEN row for the same key (PR #12 review). The V19
	 * partial unique index is the hard backstop; this read lets the service skip gracefully instead of
	 * relying on a constraint violation.
	 *
	 * @param contractId contract the drift belongs to
	 * @param checkDate  the business date the check ran for
	 * @param checkType  the drift's {@code check_type} db value (PAYMENT_CASH / ALLOCATION_PAYMENT /
	 *                   RECEIVABLE_INSTALLMENT)
	 * @return {@code true} when an OPEN row for that key already exists
	 */
	boolean existsByContractIdAndCheckDateAndCheckTypeAndStatus(UUID contractId, LocalDate checkDate,
			String checkType, String status);
}
