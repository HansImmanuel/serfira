package com.serfira.ledger.application;

import com.serfira.ledger.domain.JournalEntry;
import com.serfira.ledger.domain.LedgerPosting;
import com.serfira.ledger.domain.LedgerPostingException;
import com.serfira.ledger.infrastructure.JournalEntryRepository;
import com.serfira.shared.clock.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * The ledger's only write port: turns a balanced {@link LedgerPosting} into an immutable journal entry
 * (ADR-002, ADR-008).
 *
 * <p>Runs inside the caller's transaction ({@link Propagation#MANDATORY}): the journal entry and the
 * business write it describes must commit or roll back together, so a rolled-back payment can never
 * leave orphaned money movement in the ledger. Module direction is caller → ledger; the ledger module
 * depends on nothing (02_TECH_SPEC.md §1).
 *
 * <p>The balance invariant ({@code Σ debit = Σ credit}) is enforced by {@link LedgerPosting} itself, and
 * independently by the database's deferred constraint trigger ({@code trg_journal_entry_balance_deferred},
 * V3) at commit time. What this service adds is the event-level guard: one non-reversal entry per
 * {@code (ref_type, ref_id)}.
 *
 * <p>There is no API surface in C1 — payment (C3) and billing (C4) are the callers.
 */
@Service
public class LedgerPostingService {

	private static final Logger LOGGER = LoggerFactory.getLogger(LedgerPostingService.class);

	private final JournalEntryRepository entries;
	private final Clock clock;

	public LedgerPostingService(JournalEntryRepository entries, Clock clock) {
		this.entries = entries;
		this.clock = clock;
	}

	/**
	 * Posts one journal entry for a financial event.
	 *
	 * @param posting a validated, balanced posting; its {@code entryDate} is the event's business date
	 * @return the persisted entry, with {@code postedAt} taken from the application clock
	 * @throws LedgerPostingException when the event already has a non-reversal entry
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public JournalEntry post(LedgerPosting posting) {
		Objects.requireNonNull(posting, "posting");
		if (!posting.isReversal()
				&& entries.existsByRefTypeAndRefIdAndReversalOfIdIsNull(posting.refType(), posting.refId())) {
			throw new LedgerPostingException(posting.refType() + " " + posting.refId() + " is already posted");
		}

		JournalEntry entry = entries.saveAndFlush(new JournalEntry(posting, clock.now()));
		LOGGER.info("Posted {} journal entry {} for {} {} (debit = credit = {})",
				posting.isReversal() ? "reversal" : "", entry.getId(), posting.refType(), posting.refId(),
				posting.totalDebit());
		return entry;
	}
}
