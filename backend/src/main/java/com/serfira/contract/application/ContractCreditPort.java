package com.serfira.contract.application;

import java.util.UUID;

/**
 * The {@code contract} module's seam for booking a durable customer credit when a payment overpays
 * (04_GAPS_ADDENDUM.md §2.1, task T14) — 02_TECH_SPEC.md §1 "module berkomunikasi via interface
 * (application service) milik module lain", ADR-010.
 *
 * <p>Why a port and not the entity: {@code contract_credit} is owned by {@code contract}, so even though
 * the excess is discovered by the {@code payment} write path, the credit row is written here. The caller
 * passes plain ids and an amount; it never holds a {@code ContractCredit} entity. This mirrors
 * {@link InstallmentReceivablePort}: the two modules communicate through a value-only interface, and the
 * DB FK from {@code contract_credit.source_payment_allocation_id} to {@code payment_allocation} is a
 * database relationship only, not a JPA association across modules.
 *
 * <p>Called inside the caller's transaction (it joins it, never starts one), so the credit row and the
 * payment's EXCESS → {@code TITIPAN_NASABAH} journal commit or roll back together.
 */
public interface ContractCreditPort {

	/**
	 * Books one AVAILABLE {@code contract_credit} row for a payment's EXCESS allocation. The existing
	 * EXCESS → {@code TITIPAN_NASABAH} journal is unchanged — this row is the liability sub-ledger, not a
	 * second journal entry (task T14, Addendum §2).
	 *
	 * @param command the contract, source EXCESS allocation id and excess amount
	 * @return the id of the new {@code contract_credit} row
	 * @throws org.springframework.dao.DataIntegrityViolationException if a credit already exists for the
	 *         same allocation ({@code uk_contract_credit_source}); the EXCESS allocation id is unique per
	 *         payment, so this signals a programming error (a double call), not a retryable race
	 */
	UUID recordExcessCredit(RecordExcessCreditCommand command);
}
