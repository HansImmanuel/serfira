package com.serfira.penalty.domain.consistency;

/**
 * The three independent reconciliation checks the daily consistency step runs per contract
 * (Addendum §7.1, F1 / story L-3). The {@code dbValue} is the exact string stored in
 * {@code reconciliation_exception.check_type}, pinned to the V1 {@code ck_recon_exception_type} CHECK
 * constraint — adding a value here without widening that constraint would fail at insert.
 *
 * <ul>
 *   <li>{@link #PAYMENT_CASH} — {@code Σ} POSTED payment amount vs. the net {@code KAS} received through
 *       {@code PAYMENT} journal entries (check A).</li>
 *   <li>{@link #ALLOCATION_PAYMENT} — {@code Σ} POSTED payment allocation amount vs. {@code Σ} POSTED
 *       payment amount, i.e. invariant 6 (check B).</li>
 *   <li>{@link #RECEIVABLE_INSTALLMENT} — {@code Σ} installment receivable residual (principal + recognized
 *       interest + effective penalty residual) vs. the net {@code PIUTANG_*} ledger balance (check C).</li>
 * </ul>
 */
public enum ConsistencyCheckType {

	PAYMENT_CASH("PAYMENT_CASH"),
	ALLOCATION_PAYMENT("ALLOCATION_PAYMENT"),
	RECEIVABLE_INSTALLMENT("RECEIVABLE_INSTALLMENT");

	private final String dbValue;

	ConsistencyCheckType(String dbValue) {
		this.dbValue = dbValue;
	}

	/** The exact {@code reconciliation_exception.check_type} string (V1 {@code ck_recon_exception_type}). */
	public String dbValue() {
		return dbValue;
	}
}
