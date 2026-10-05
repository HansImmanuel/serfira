package com.serfira.contract.domain;

/**
 * Lifecycle of a {@code contract_credit} row (04_GAPS_ADDENDUM.md §2.1, GLOSSARY ContractCredit).
 *
 * <p>A credit is born {@link #AVAILABLE} when an overpayment produces an EXCESS allocation and moves to
 * {@link #APPLIED} only when its available balance reaches 0 ({@code amount − Σ applications = 0}).
 * {@link #REFUNDED} is reserved for the full-refund flow and is out of the MVP scope (Addendum §2.1).
 *
 * <p>Persisted with {@code @Enumerated(EnumType.STRING)}; the value set mirrors the DB
 * {@code ck_contract_credit_status} CHECK (V1).
 */
public enum ContractCreditStatus {

	/** Credit has unused balance and can fund an application. */
	AVAILABLE,

	/** Credit is fully consumed ({@code Σ applications = amount}); it can fund nothing more. */
	APPLIED,

	/** Credit was refunded in full to the customer (future flow, not written in the MVP). */
	REFUNDED
}
