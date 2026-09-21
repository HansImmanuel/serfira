package com.serfira.ledger.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A posted {@link JournalEntry} is materialized from a validated {@link LedgerPosting}: the entry carries
 * the event reference and the business date, and its lines inherit that date and the contract scope
 * (03_DOMAIN_MODEL.md §1.13, ADR-008). No database is involved — the persistence behaviour is covered by
 * {@code LedgerPostingIT}.
 */
class JournalEntryTest {

	private static final UUID EVENT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
	private static final UUID CONTRACT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
	private static final OffsetDateTime ENTRY_DATE = OffsetDateTime.of(2026, 1, 31, 0, 0, 0, 0,
			ZoneOffset.ofHours(7));
	private static final OffsetDateTime POSTED_AT = OffsetDateTime.of(2026, 1, 31, 9, 30, 0, 0,
			ZoneOffset.ofHours(7));

	@Test
	void materializesThePostingLinesWithTheBusinessDateAndContractScope() {
		JournalEntry entry = new JournalEntry(disbursement(), POSTED_AT);

		assertThat(entry.getId()).isNull();
		assertThat(entry.getEntryDate()).isEqualTo(ENTRY_DATE);
		assertThat(entry.getPostedAt()).isEqualTo(POSTED_AT);
		assertThat(entry.getLines()).hasSize(2);
		assertThat(entry.getLines()).extracting(JournalLine::getEntryDate).containsOnly(ENTRY_DATE);
		assertThat(entry.getLines()).extracting(JournalLine::getAccount)
				.containsExactly(LedgerAccount.PIUTANG_POKOK, LedgerAccount.KAS);
		assertThat(entry.getLines()).extracting(JournalLine::getContractId).containsOnly(CONTRACT_ID);
		assertThat(entry.getLines()).extracting(JournalLine::getDebit)
				.containsExactly(new BigDecimal("16000000.00"), new BigDecimal("0.00"));
		assertThat(entry.getLines()).extracting(JournalLine::getCredit)
				.containsExactly(new BigDecimal("0.00"), new BigDecimal("16000000.00"));
	}

	@Test
	void recordsTheEventReferenceAndDescription() {
		JournalEntry entry = new JournalEntry(disbursement(), POSTED_AT);

		assertThat(entry.getRefType()).isEqualTo(LedgerRefType.CONTRACT_ACTIVATION);
		assertThat(entry.getRefId()).isEqualTo(EVENT_ID);
		assertThat(entry.getDescription()).isEqualTo("Disbursement");
		assertThat(entry.getReversalOfId()).isNull();
		// Every entry carries a contract scope for the receivable reconciliation (Addendum §7.1, check C).
		assertThat(entry.getLines()).allSatisfy(line -> assertThat(line.getContractId()).isEqualTo(CONTRACT_ID));
	}

	@Test
	void carriesTheReversalReferenceOfACorrectingPosting() {
		UUID originalId = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
		LedgerPosting reversal = new LedgerPosting(LedgerRefType.CONTRACT_ACTIVATION, EVENT_ID, ENTRY_DATE,
				"Reversal", originalId, List.of(
						LedgerPostingLine.debit(LedgerAccount.KAS, new BigDecimal("16000000.00"), CONTRACT_ID),
						LedgerPostingLine.credit(LedgerAccount.PIUTANG_POKOK, new BigDecimal("16000000.00"),
								CONTRACT_ID)));

		JournalEntry entry = new JournalEntry(reversal, POSTED_AT);

		assertThat(entry.getReversalOfId()).isEqualTo(originalId);
	}

	@Test
	void linesAreExposedUnmodifiable() {
		JournalEntry entry = new JournalEntry(disbursement(), POSTED_AT);

		assertThatThrownBy(() -> entry.getLines().clear()).isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void theEntryBalanceEqualsThePostingTotals() {
		LedgerPosting posting = disbursement();
		JournalEntry entry = new JournalEntry(posting, POSTED_AT);

		BigDecimal debit = entry.getLines().stream().map(JournalLine::getDebit).reduce(BigDecimal.ZERO,
				BigDecimal::add);
		BigDecimal credit = entry.getLines().stream().map(JournalLine::getCredit).reduce(BigDecimal.ZERO,
				BigDecimal::add);

		assertThat(debit).isEqualByComparingTo(posting.totalDebit());
		assertThat(credit).isEqualByComparingTo(posting.totalCredit());
		assertThat(debit).isEqualByComparingTo(credit);
	}

	@Test
	void aPostingInstantIsRequired() {
		assertThatThrownBy(() -> new JournalEntry(disbursement(), null))
				.isInstanceOf(NullPointerException.class);
	}

	private static LedgerPosting disbursement() {
		return LedgerPosting.of(LedgerRefType.CONTRACT_ACTIVATION, EVENT_ID, ENTRY_DATE, "Disbursement", List.of(
				LedgerPostingLine.debit(LedgerAccount.PIUTANG_POKOK, new BigDecimal("16000000.00"), CONTRACT_ID),
				LedgerPostingLine.credit(LedgerAccount.KAS, new BigDecimal("16000000.00"), CONTRACT_ID)));
	}
}
