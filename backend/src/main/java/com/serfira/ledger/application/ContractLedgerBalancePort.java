package com.serfira.ledger.application;

import java.util.UUID;

/**
 * The {@code ledger} module's read seam for the daily consistency check (F1, task T17, Addendum §7.1).
 *
 * <p>{@code journal_entry}/{@code journal_line} are {@code ledger}-owned, so the consistency step (hosted
 * in {@code penalty}) reads the per-contract ledger balances it reconciles through this port, never by
 * querying those tables (02_TECH_SPEC.md §1). {@code ledger} depends on no other module, so this is a clean
 * read-only seam with no new edge for {@code ledger} itself; {@code penalty} already depends on
 * {@code ledger} for posting.
 *
 * <p>Read-only and side-effect free: the ledger is append-only and this port only aggregates.
 */
public interface ContractLedgerBalancePort {

	/**
	 * The contract's ledger-side reconciliation balances (net {@code KAS} from {@code PAYMENT} entries and
	 * net {@code PIUTANG_*} receivable), in one read.
	 *
	 * @param contractId contract whose balances are needed
	 * @return the balances; zeroes for a contract with no posted lines
	 */
	ContractLedgerBalance loadContractBalance(UUID contractId);
}
