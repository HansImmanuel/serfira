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
	 * <p>Filters are optional via the {@code :param is null} idiom. The {@code refType}, {@code refId},
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
				and (:fromInclusive is null or line.entryDate >= :fromInclusive)
				and (:toExclusive is null or line.entryDate < :toExclusive)
				and (:refType is null or entry.refType = :refType)
			order by line.entryDate asc, line.id asc
			""",
			countQuery = """
			select count(line)
			from JournalLine line
				join line.journalEntry entry
			where line.contractId = :contractId
				and (:fromInclusive is null or line.entryDate >= :fromInclusive)
				and (:toExclusive is null or line.entryDate < :toExclusive)
				and (:refType is null or entry.refType = :refType)
			""")
	Page<StatementLineProjection> findContractStatement(
			@Param("contractId") UUID contractId,
			@Param("fromInclusive") OffsetDateTime fromInclusive,
			@Param("toExclusive") OffsetDateTime toExclusive,
			@Param("refType") LedgerRefType refType,
			Pageable pageable);

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
