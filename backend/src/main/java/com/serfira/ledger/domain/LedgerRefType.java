package com.serfira.ledger.domain;

/**
 * Vocabulary of {@code journal_entry.ref_type}: which financial event produced a journal entry
 * (02_TECH_SPEC.md §3 posting-rules table, 03_DOMAIN_MODEL.md §1.13, ADR-008).
 *
 * <p>Together with {@code ref_id} this identifies the event, which is what reconciliation (L-3,
 * Addendum §7.1) and the {@code (ref_type, ref_id)} index use. A <b>reversal</b> keeps the original
 * event's type and is recognized by {@code reversal_of_id} being set, so both sides of a reversed
 * event group under one {@code (ref_type, ref_id)}.
 *
 * <p>Stored in a VARCHAR(40) column with no CHECK constraint, so extending this enum needs no
 * migration (ADR-008).
 */
public enum LedgerRefType {

	/** Contract activation / disbursement — {@code PIUTANG_POKOK} debit, {@code KAS} credit. */
	CONTRACT_ACTIVATION,

	/** Regular payment received — {@code KAS} debit; penalty/interest/principal/credit credits. */
	PAYMENT,

	/** Interest billing at the due date (C4) — {@code PIUTANG_BUNGA} debit, {@code PENDAPATAN_BUNGA} credit. */
	BILLING,

	/** Daily penalty accrual (D1/D2) — {@code PIUTANG_DENDA} debit, {@code PENDAPATAN_DENDA} credit. */
	PENALTY_ACCRUAL,

	/** Penalty waive/reduction (E5) — {@code PENDAPATAN_DENDA} debit, {@code PIUTANG_DENDA} credit. */
	PENALTY_WAIVER,

	/** Customer credit applied to an installment (E3) — {@code TITIPAN_NASABAH} debit, receivable credits. */
	CREDIT_APPLICATION,

	/** Settlement execution (E2) — cash, rebate, admin fee and consumed credit entries. */
	SETTLEMENT,

	/** Receivable write-off (F5) — {@code BIAYA_PENGHAPUSAN_PIUTANG} debit, receivable credits. */
	WRITE_OFF
}
