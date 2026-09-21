package com.serfira.ledger.domain;

/**
 * A ledger invariant was violated while building or posting a journal entry: an unbalanced entry, a
 * malformed line, an event that is already posted, or a posting attempted outside a transaction.
 *
 * <p>Deliberately a plain {@link RuntimeException} rather than a {@code SerfiraException}: these are
 * internal consistency failures, not client mistakes. No API surface posts to the ledger (ADR-008), and
 * a client could not repair an unbalanced journal anyway, so the failure must reach the standard error
 * handler as 500 {@code INTERNAL_ERROR} instead of a 4xx code.
 */
public class LedgerPostingException extends RuntimeException {

	public LedgerPostingException(String message) {
		super(message);
	}
}
