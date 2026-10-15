package com.serfira.contract.application;

import java.math.BigDecimal;
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

	/**
	 * The contract's available customer credit: {@code Σ (amount − Σ applications)} over its AVAILABLE
	 * {@code contract_credit} rows (04_GAPS_ADDENDUM.md §2.1). The settlement quote snapshots this as
	 * {@code settlement_quote.available_credit} (ADR-018 D6); it does not consume it — consumption is T13.
	 *
	 * <p>Same derivation the credit read ({@code GET …/credit}) and the apply path use, so the three views
	 * agree. {@code contract} owns {@code contract_credit}, so the figure is produced here, not read from
	 * the table by the settlement module.
	 *
	 * @param contractId contract whose available credit is needed
	 * @return the available balance, scale-2, {@code >= 0}; zero when the contract has no AVAILABLE credit
	 */
	BigDecimal availableCredit(UUID contractId);

	/**
	 * Consumes <b>all</b> of the contract's AVAILABLE customer credit for an early settlement and flips each
	 * consumed {@code contract_credit} to {@code APPLIED} (ADR-018 D10: a settlement must consume all
	 * available credit). Returns the available balance consumed from each source so the {@code settlement}
	 * module can write one {@code settlement_credit_application} row per source and the single
	 * {@code TITIPAN_NASABAH} journal line.
	 *
	 * <p>The sum of the returned amounts equals {@link #availableCredit(UUID)} at the moment of the call.
	 * This method flips status only and writes no {@code contract_credit_application} rows — settlement
	 * consumption is recorded in the settlement-owned {@code settlement_credit_application}, which the V17
	 * migration teaches the V14 balance/status guards to count, so a credit flipped {@code APPLIED} here is
	 * coherent at commit (its combined applied total equals its amount). The caller must have already
	 * revalidated the quote and must write the matching {@code settlement_credit_application} rows in the
	 * same transaction.
	 *
	 * <p>Called inside the settlement use case's transaction ({@code Propagation.MANDATORY}); the status
	 * flips commit with the settlement, its allocations and its journal.
	 *
	 * @param contractId  contract whose AVAILABLE credit to consume
	 * @param consumedAt  business instant of the settlement (reserved for audit; the row's audit columns
	 *                    are stamped from the clock)
	 * @return one entry per consumed AVAILABLE credit, each a positive scale-2 amount; empty when the
	 *         contract has no AVAILABLE credit
	 */
	java.util.List<ConsumedCredit> consumeAllAvailableCredit(UUID contractId, java.time.OffsetDateTime consumedAt);

	/**
	 * Reverses the customer credit a voided payment's EXCESS allocation(s) booked, when it is still
	 * <b>entirely AVAILABLE</b> (E4, task T16, ADR-020 D4). Called by the payment void inside its
	 * transaction after the void has decided the payment is eligible.
	 *
	 * <p>Concurrency-safe (ADR-020 D4): each candidate {@code contract_credit} row is locked
	 * {@code FOR UPDATE} before its available balance is read, so a concurrent credit-apply or settlement
	 * cannot consume it between the check and the reversal. If any such credit has been partially or fully
	 * applied (regular apply, T14) or consumed by a settlement (T13) — available balance {@code <} amount,
	 * or status no longer {@code AVAILABLE} — the whole void is rejected with
	 * {@code PaymentCreditConsumedException} (409) and nothing is written.
	 *
	 * <p>An entirely-AVAILABLE credit is reversed by removing the sub-ledger row: the credit is only the
	 * sub-ledger of the EXCESS → {@code TITIPAN_NASABAH} journal line, and the void's reversal entry debits
	 * {@code TITIPAN_NASABAH} back, so the credit must disappear for {@code Σ AVAILABLE contract_credit} to
	 * keep reconciling with the {@code TITIPAN_NASABAH} ledger balance (Addendum §7, T17 check 3). The money
	 * trail is preserved by the immutable journal entries (original + reversal), not by the sub-ledger row.
	 * A credit with no applications has nothing in {@code contract_credit_application} to orphan.
	 *
	 * @param sourcePaymentAllocationIds the EXCESS allocation ids of the payment being voided; may be empty
	 *                                   (the payment produced no EXCESS), in which case this is a no-op
	 * @param reversedAt                 business instant of the void (for audit/logging)
	 * @return the total credit amount reversed, scale-2, {@code >= 0}; zero when there was no credit
	 * @throws com.serfira.shared.error.PaymentCreditConsumedException if any matched credit is not entirely
	 *         AVAILABLE
	 */
	BigDecimal reverseAvailableCreditFromAllocations(java.util.Set<UUID> sourcePaymentAllocationIds,
			java.time.OffsetDateTime reversedAt);
}
