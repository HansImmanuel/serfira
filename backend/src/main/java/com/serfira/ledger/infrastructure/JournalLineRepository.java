package com.serfira.ledger.infrastructure;

import com.serfira.ledger.domain.JournalLine;
import com.serfira.ledger.domain.LedgerAccount;
import com.serfira.ledger.domain.LedgerRefType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Read access to {@link JournalLine} for the contract statement (T9). Lines are append-only, so there is
 * deliberately no update or delete path (V1 triggers reject them); this interface only reads.
 */
public interface JournalLineRepository extends JpaRepository<JournalLine, UUID> {

	/**
	 * One page of a contract's journal lines, filtered by a half-open {@code [from, to)} business-time
	 * window and an optional event type, oldest first.
	 *
	 * <p>Filters are optional. Each nullable bind is wrapped in {@code cast(... as timestamp/string)} in its
	 * {@code is null} guard so PostgreSQL has a declared parameter type even when the bind is {@code null};
	 * a bare {@code :param is null} leaves the bind untyped and PostgreSQL rejects it with {@code 42P18}
	 * "could not determine data type of parameter" (X-13). The casts touch only the guard — the real
	 * comparisons ({@code >= :fromInclusive}, {@code < :toExclusive}, {@code = :refType}) are unchanged, so
	 * the half-open {@code [from, to)} window and the optional exact {@code refType} match are preserved.
	 * The {@code refType}, {@code refId},
	 * {@code description} and {@code reversalOfId} come from the parent {@code journalEntry}, joined here so
	 * each line is one projected row without a lazy walk. Ordering is {@code entry_date} then the line id, a
	 * stable tiebreak so paging is deterministic even for the several lines of one entry (which share the
	 * entry's {@code entry_date}).
	 *
	 * <p>The projection carries the {@link LedgerAccount} enum; the human-readable account name is a
	 * constant label on the enum ({@link LedgerAccount#displayName()}), resolved by the application service
	 * when it maps the projection, so no join to the {@code accounts} table is needed.
	 */
	@Query(value = """
			select
				line.entryDate as entryDate,
				line.account as account,
				entry.description as description,
				entry.refType as refType,
				entry.refId as refId,
				line.debit as debit,
				line.credit as credit,
				entry.reversalOfId as reversalOfId
			from JournalLine line
				join line.journalEntry entry
			where line.contractId = :contractId
				and (cast(:fromInclusive as timestamp) is null or line.entryDate >= :fromInclusive)
				and (cast(:toExclusive as timestamp) is null or line.entryDate < :toExclusive)
				and (cast(:refType as string) is null or entry.refType = :refType)
			order by line.entryDate asc, line.id asc
			""",
			countQuery = """
			select count(line)
			from JournalLine line
				join line.journalEntry entry
			where line.contractId = :contractId
				and (cast(:fromInclusive as timestamp) is null or line.entryDate >= :fromInclusive)
				and (cast(:toExclusive as timestamp) is null or line.entryDate < :toExclusive)
				and (cast(:refType as string) is null or entry.refType = :refType)
			""")
	Page<StatementLineProjection> findContractStatement(
			@Param("contractId") UUID contractId,
			@Param("fromInclusive") OffsetDateTime fromInclusive,
			@Param("toExclusive") OffsetDateTime toExclusive,
			@Param("refType") LedgerRefType refType,
			Pageable pageable);

	/**
	 * Net movement {@code Σ(debit − credit)} of one contract's lines on a single account, across every
	 * journal entry regardless of {@code ref_type} or reversal, for the daily consistency check (F1, task
	 * T17, Addendum §7.1 check C, {@code RECEIVABLE_INSTALLMENT}). A reversal entry already carries its own
	 * swapped line, so summing every line nets reversed events to zero without a {@code reversal_of_id}
	 * filter. {@code coalesce} yields zero when the contract has no line on that account.
	 *
	 * @param contractId contract whose balance is needed
	 * @param account    the chart-of-accounts account to sum (a {@code PIUTANG_*} receivable here)
	 */
	@Query("""
			select coalesce(sum(line.debit - line.credit), 0)
			from JournalLine line
			where line.contractId = :contractId and line.account = :account
			""")
	BigDecimal netBalance(@Param("contractId") UUID contractId, @Param("account") LedgerAccount account);

	/**
	 * Net movement {@code Σ(debit − credit)} of one contract's lines on a single account, restricted to the
	 * journal entries of one event type, for the daily consistency check (F1, task T17, Addendum §7.1 check
	 * A, {@code PAYMENT_CASH}): the net {@code KAS} received through {@code PAYMENT} entries. A payment debits
	 * {@code KAS}; its void posts a reversal that credits {@code KAS} back, so summing the PAYMENT-typed
	 * {@code KAS} lines yields the cash of the still-POSTED payments — the same active view
	 * {@link com.serfira.payment.application.PaymentReconciliationPort} reports. {@code coalesce} yields zero
	 * when no such line exists.
	 *
	 * @param contractId contract whose balance is needed
	 * @param account    the chart-of-accounts account to sum ({@code KAS} here)
	 * @param refType    the single event type whose entries are summed ({@code PAYMENT} here)
	 */
	@Query("""
			select coalesce(sum(line.debit - line.credit), 0)
			from JournalLine line
				join line.journalEntry entry
			where line.contractId = :contractId
				and line.account = :account
				and entry.refType = :refType
			""")
	BigDecimal netBalanceByRefType(@Param("contractId") UUID contractId,
			@Param("account") LedgerAccount account, @Param("refType") LedgerRefType refType);

	/** One projected statement line: the posted {@code journal_line} plus its parent entry's event fields. */
	interface StatementLineProjection {

		OffsetDateTime getEntryDate();

		LedgerAccount getAccount();

		String getDescription();

		LedgerRefType getRefType();

		UUID getRefId();

		BigDecimal getDebit();

		BigDecimal getCredit();

		UUID getReversalOfId();
	}
}
