package com.serfira.ledger.domain;

/**
 * Chart of accounts of the double-entry ledger (03_DOMAIN_MODEL.md §1.12, ADR-002; rows seeded by V1).
 *
 * <p>Posting rules reference these constants instead of string literals, so a typo cannot reach the
 * database and every account the code may touch is visible in one place. The {@code accounts} table
 * remains the authority for a code's existence: {@code journal_line.account_code} is a foreign key,
 * and {@code LedgerPostingIT} asserts that every constant below exists in the seeded chart.
 *
 * <p>Account types are not modelled here — nothing in the posting path branches on them.
 */
public enum LedgerAccount {

	/** Cash received/paid by the company. */
	KAS,

	/** Receivable — financing principal. */
	PIUTANG_POKOK,

	/** Receivable — interest, only after billing/recognition. */
	PIUTANG_BUNGA,

	/** Receivable — penalty, only after daily accrual. */
	PIUTANG_DENDA,

	/** Liability — customer credit / excess payment held by the company. */
	TITIPAN_NASABAH,

	/** Revenue — interest. */
	PENDAPATAN_BUNGA,

	/** Revenue — penalty. */
	PENDAPATAN_DENDA,

	/** Revenue — settlement admin fee. */
	PENDAPATAN_ADMIN,

	/** Expense (contra-receivable) — settlement rebate. */
	DISKON_PELUNASAN,

	/** Expense — receivable write-off. */
	BIAYA_PENGHAPUSAN_PIUTANG;

	/**
	 * Persisted account code. Kept as an explicit mapping point between the ledger vocabulary and the
	 * chart-of-accounts codes so a future rename cannot silently change what is written.
	 */
	public String code() {
		return name();
	}
}
