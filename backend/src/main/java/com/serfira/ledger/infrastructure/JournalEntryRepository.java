package com.serfira.ledger.infrastructure;

import com.serfira.ledger.domain.JournalEntry;
import com.serfira.ledger.domain.LedgerRefType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.UUID;

/**
 * Persistence for {@link JournalEntry}. Rows are append-only: there is deliberately no update or delete
 * path (V1 triggers reject them anyway), only inserts and reads.
 */
public interface JournalEntryRepository extends JpaRepository<JournalEntry, UUID> {

	/**
	 * Posting guard (ADR-008): a financial event may have at most one <b>non-reversal</b> journal entry,
	 * so a retried or replayed posting cannot double-book the same event. Reversal entries (story E4)
	 * are intentionally exempt — they are the documented correction mechanism.
	 */
	boolean existsByRefTypeAndRefIdAndReversalOfIdIsNull(LedgerRefType refType, UUID refId);

	/**
	 * The single non-reversal journal entry of an event, with its lines fetched (E4 void, task T16): a
	 * reversal mirrors these lines with debit/credit swapped. Empty when the event was never posted. The
	 * posting guard ({@code existsByRefTypeAndRefIdAndReversalOfIdIsNull}) means there is at most one such
	 * entry per {@code (ref_type, ref_id)}.
	 */
	@Query("""
			select distinct e from JournalEntry e
			left join fetch e.lines
			where e.refType = :refType and e.refId = :refId and e.reversalOfId is null
			""")
	java.util.Optional<JournalEntry> findOriginalWithLines(
			@org.springframework.data.repository.query.Param("refType") LedgerRefType refType,
			@org.springframework.data.repository.query.Param("refId") UUID refId);
}
