package com.serfira.ledger.infrastructure;

import com.serfira.ledger.domain.JournalEntry;
import com.serfira.ledger.domain.LedgerRefType;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
