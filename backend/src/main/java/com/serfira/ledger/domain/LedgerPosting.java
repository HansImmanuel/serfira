package com.serfira.ledger.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A balanced set of journal lines for one financial event, ready to be posted
 * (02_TECH_SPEC.md §3, ADR-002, ADR-008).
 *
 * <p>This is where the ledger invariants live, so no caller can build an entry that violates them:
 * the event reference and business date are required, at least two lines are present, and
 * {@code Σ debit = Σ credit} holds. The database's deferred constraint trigger
 * ({@code trg_journal_entry_balance_deferred}, V3) is the independent backstop for writes that bypass
 * this object.
 *
 * <p>{@code entryDate} is the <b>business</b> date of the event (activation date, payment {@code paid_at},
 * billing due date, accrual date), not the moment of recording — that is {@code posted_at}, set from the
 * application clock by {@code LedgerPostingService}. Both are frozen once inserted.
 */
public record LedgerPosting(LedgerRefType refType, UUID refId, OffsetDateTime entryDate, String description,
		UUID reversalOfId, List<LedgerPostingLine> lines) {

	/** Every money movement needs at least one debit and one credit (PRD §3: "minimal 2 baris"). */
	private static final int MIN_LINES = 2;

	private static final int MAX_DESCRIPTION_LENGTH = 255;

	public LedgerPosting {
		Objects.requireNonNull(refType, "refType");
		Objects.requireNonNull(refId, "refId");
		Objects.requireNonNull(entryDate, "entryDate");
		Objects.requireNonNull(lines, "lines");
		if (lines.stream().anyMatch(Objects::isNull)) {
			throw new LedgerPostingException("a journal entry cannot contain a null line");
		}
		description = normalizeDescription(description);
		lines = List.copyOf(lines);
		if (lines.size() < MIN_LINES) {
			throw new LedgerPostingException(refType + " " + refId + " must post at least " + MIN_LINES
					+ " journal lines but had " + lines.size());
		}
		BigDecimal debit = totalOf(lines, true);
		BigDecimal credit = totalOf(lines, false);
		if (debit.compareTo(credit) != 0) {
			throw new LedgerPostingException(refType + " " + refId + " is unbalanced: debit = " + debit
					+ ", credit = " + credit);
		}
	}

	/** A posting of a new financial event (no reversal). */
	public static LedgerPosting of(LedgerRefType refType, UUID refId, OffsetDateTime entryDate, String description,
			List<LedgerPostingLine> lines) {
		return new LedgerPosting(refType, refId, entryDate, description, null, lines);
	}

	public BigDecimal totalDebit() {
		return totalOf(lines, true);
	}

	public BigDecimal totalCredit() {
		return totalOf(lines, false);
	}

	/** {@code true} when this entry corrects an earlier one instead of recording a new event (E4). */
	public boolean isReversal() {
		return reversalOfId != null;
	}

	private static BigDecimal totalOf(List<LedgerPostingLine> lines, boolean debitSide) {
		return lines.stream()
				.map(line -> debitSide ? line.debit() : line.credit())
				.reduce(BigDecimal.ZERO, BigDecimal::add)
				.setScale(2, RoundingMode.UNNECESSARY);
	}

	private static String normalizeDescription(String description) {
		if (description == null) {
			return null;
		}
		String trimmed = description.trim();
		if (trimmed.isEmpty()) {
			return null;
		}
		if (trimmed.length() > MAX_DESCRIPTION_LENGTH) {
			throw new LedgerPostingException("journal entry description must be at most " + MAX_DESCRIPTION_LENGTH
					+ " characters but had " + trimmed.length());
		}
		return trimmed;
	}
}
