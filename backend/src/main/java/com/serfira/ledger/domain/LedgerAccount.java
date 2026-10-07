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
	KAS("Cash on hand"),

	/** Receivable — financing principal. */
	PIUTANG_POKOK("Receivable — principal"),

	/** Receivable — interest, only after billing/recognition. */
	PIUTANG_BUNGA("Receivable — interest (recognized)"),

	/** Receivable — penalty, only after daily accrual. */
	PIUTANG_DENDA("Receivable — penalty"),

	/** Liability — customer credit / excess payment held by the company. */
	TITIPAN_NASABAH("Customer credit / overpayment"),

	/** Revenue — interest. */
	PENDAPATAN_BUNGA("Revenue — interest"),

	/** Revenue — penalty. */
	PENDAPATAN_DENDA("Revenue — penalty"),

	/** Revenue — settlement admin fee. */
	PENDAPATAN_ADMIN("Revenue — settlement admin fee"),

	/** Expense (contra-receivable) — settlement rebate. */
	DISKON_PELUNASAN("Expense — settlement rebate"),

	/** Expense — receivable write-off. */
	BIAYA_PENGHAPUSAN_PIUTANG("Expense — receivable write-off"),

	/** Expense — penalty waiver / reduction (E5, ADR-019 D3; seeded by V15). */
	BEBAN_WAIVER_DENDA("Expense — penalty waiver");

	private final String displayName;

	LedgerAccount(String displayName) {
		this.displayName = displayName;
	}

	/**
	 * Persisted account code. Kept as an explicit mapping point between the ledger vocabulary and the
	 * chart-of-accounts codes so a future rename cannot silently change what is written.
	 */
	public String code() {
		return name();
	}

	/**
	 * Human-readable account name, mirroring the seeded {@code accounts.name} (V1). Kept on the enum so a
	 * read path (the contract statement, T9) can render "account_code (+ name)" without joining the
	 * {@code accounts} table or mapping a new entity. The {@code accounts} table stays the authority for a
	 * code's existence; this is a display label only, pinned to the seed by {@code LedgerAccountNameIT}.
	 */
	public String displayName() {
		return displayName;
	}
}
