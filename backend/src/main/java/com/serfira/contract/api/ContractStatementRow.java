package com.serfira.contract.api;

import com.serfira.ledger.application.ContractStatementLine;
import com.serfira.ledger.domain.LedgerRefType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row of a contract statement (rekening koran, T9 / FE §2.7): a single posted {@code journal_line}
 * that touches the contract. The statement is ledger-literal (ADR-013 A-8): {@code debit}/{@code credit}
 * are the line's own posted amounts, not a running balance and not re-interpreted into a customer view.
 * A reversal entry (correction) appears as its own row, flagged by {@code isReversal} — history is never
 * hidden (ledger is append-only).
 *
 * @param entryDate   business date the entry was posted for
 * @param accountCode chart-of-accounts code of the line
 * @param accountName human-readable account name for the code
 * @param description parent entry description (may be {@code null})
 * @param refType     the financial event the entry records (PAYMENT, BILLING, PENALTY_ACCRUAL, …)
 * @param refId       id of the event aggregate (the "contract ref" link to a payment/settlement/…)
 * @param debit       posted debit for this line (scale 2)
 * @param credit      posted credit for this line (scale 2)
 * @param isReversal  whether this line belongs to a reversal (correction) entry
 * @param reversalOfId the entry this one reverses, or {@code null} when it is not a reversal
 */
public record ContractStatementRow(
		OffsetDateTime entryDate,
		String accountCode,
		String accountName,
		String description,
		LedgerRefType refType,
		UUID refId,
		BigDecimal debit,
		BigDecimal credit,
		boolean isReversal,
		UUID reversalOfId) {

	public static ContractStatementRow from(ContractStatementLine line) {
		return new ContractStatementRow(
				line.entryDate(),
				line.accountCode(),
				line.accountName(),
				line.description(),
				line.refType(),
				line.refId(),
				line.debit(),
				line.credit(),
				line.isReversal(),
				line.reversalOfId());
	}
}
