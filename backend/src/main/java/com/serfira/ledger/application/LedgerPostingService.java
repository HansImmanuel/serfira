package com.serfira.ledger.application;

import com.serfira.ledger.domain.JournalEntry;
import com.serfira.ledger.domain.LedgerPosting;
import com.serfira.ledger.domain.LedgerPostingException;
import com.serfira.ledger.domain.LedgerPostingLine;
import com.serfira.ledger.domain.LedgerRefType;
import com.serfira.ledger.infrastructure.JournalEntryRepository;
import com.serfira.shared.clock.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

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

	/**
	 * Posts a reversal of an already-posted event, mirroring every line of the original entry with its
	 * debit and credit swapped (E4 payment void, task T16, ADR-020 D1.2; ADR-002 — corrections are
	 * reversal entries, never updates/deletes). The reversal keeps the original {@code (ref_type, ref_id)}
	 * and sets {@code reversal_of_id} to the original entry id, so the two sides group under one event and
	 * the V12 {@code uq_journal_entry_event} non-reversal-only index still permits it.
	 *
	 * <p>The caller supplies only the event coordinates and the reversal's business date/description; the
	 * mirrored lines come from the stored original, so a caller cannot post an unbalanced or divergent
	 * reversal. The reversal of a balanced entry is balanced by construction.
	 *
	 * @param refType     the original event's ref type
	 * @param refId       the original event's ref id
	 * @param entryDate   business date of the reversal (e.g. the void instant)
	 * @param description reversal description (never PII)
	 * @return the persisted reversal entry
	 * @throws LedgerPostingException if the event has no non-reversal entry to reverse
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public JournalEntry postReversal(LedgerRefType refType, UUID refId, OffsetDateTime entryDate,
			String description) {
		Objects.requireNonNull(refType, "refType");
		Objects.requireNonNull(refId, "refId");
		JournalEntry original = entries.findOriginalWithLines(refType, refId)
				.orElseThrow(() -> new LedgerPostingException(
						refType + " " + refId + " has no posted entry to reverse"));
		List<LedgerPostingLine> mirrored = original.getLines().stream()
				.map(line -> new LedgerPostingLine(line.getAccount(), line.getCredit(), line.getDebit(),
						line.getContractId()))
				.toList();
		LedgerPosting reversal = new LedgerPosting(refType, refId, entryDate, description, original.getId(), mirrored);
		JournalEntry entry = entries.saveAndFlush(new JournalEntry(reversal, clock.now()));
		LOGGER.info("Posted reversal journal entry {} of {} for {} {} (debit = credit = {})",
				entry.getId(), original.getId(), refType, refId, reversal.totalDebit());
		return entry;
	}
}
