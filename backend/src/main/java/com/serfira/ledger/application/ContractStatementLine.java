package com.serfira.ledger.application;

import com.serfira.ledger.domain.LedgerRefType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * One {@code journal_line} that touches a contract, as the contract statement reads it (T9, ADR-013 A-8,
 * 06_FRONTEND_SPEC.md §2.7). The statement is <b>ledger-literal</b>: {@code debit}/{@code credit} are the
 * posted line amounts for this single line, never re-interpreted into a customer point of view and never
 * aggregated. There is no running balance in Phase 1 (the meaningful aggregate is the aging report, T8).
 *
 * <p>Exactly one of {@code debit}/{@code credit} is positive and the other is zero (the DB
 * {@code ck_journal_line_not_both}/{@code ck_journal_line_not_zero} constraints). A reversal entry
 * (correction) is surfaced through {@code reversalOfId} rather than hidden: the original line stays and the
 * reversal appears as its own row (ledger is append-only, ADR-002).
 *
 * <p>Owned by {@code ledger}, which depends on no other module. The {@code contract} module reads these
 * snapshots through {@link ContractStatementPort} and maps them to its API response, so no journal entity
 * ever crosses the module boundary (02_TECH_SPEC.md §1).
 *
 * @param entryDate    business date the entry was posted for ({@code journal_entry.entry_date})
 * @param accountCode  chart-of-accounts code of the line ({@code journal_line.account_code})
 * @param accountName  human-readable account name for the code (display label; the posted value is the code)
 * @param description  parent entry description, may be {@code null}
 * @param refType      the financial event the entry records (PAYMENT, BILLING, PENALTY_ACCRUAL, …)
 * @param refId        id of the event aggregate (payment id, installment id, …), the "contract ref" link
 * @param debit        posted debit for this line, scale 2, {@code >= 0}
 * @param credit       posted credit for this line, scale 2, {@code >= 0}
 * @param reversalOfId the entry this one reverses, or {@code null} when it is not a reversal
 */
public record ContractStatementLine(
		OffsetDateTime entryDate,
		String accountCode,
		String accountName,
		String description,
		LedgerRefType refType,
		UUID refId,
		BigDecimal debit,
		BigDecimal credit,
		UUID reversalOfId) {

	public ContractStatementLine {
		Objects.requireNonNull(entryDate, "entryDate");
		Objects.requireNonNull(accountCode, "accountCode");
		Objects.requireNonNull(accountName, "accountName");
		Objects.requireNonNull(refType, "refType");
		Objects.requireNonNull(refId, "refId");
		Objects.requireNonNull(debit, "debit");
		Objects.requireNonNull(credit, "credit");
	}

	/** Whether this line belongs to a reversal (correction) entry. */
	public boolean isReversal() {
		return reversalOfId != null;
	}
}
