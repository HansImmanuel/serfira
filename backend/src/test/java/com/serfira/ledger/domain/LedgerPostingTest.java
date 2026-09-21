package com.serfira.ledger.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit tests for the entry-level invariants: shape, and {@code Σ debit = Σ credit}
 * (03_DOMAIN_MODEL.md §3 invariant 1). The database re-checks the balance independently at commit
 * ({@code trg_journal_entry_balance_deferred}); here it must be impossible to build a bad posting at all.
 */
class LedgerPostingTest {

	private static final UUID EVENT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
	private static final UUID CONTRACT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
	private static final OffsetDateTime ENTRY_DATE = OffsetDateTime.of(2026, 1, 31, 0, 0, 0, 0,
			ZoneOffset.ofHours(7));

	@Test
	void aBalancedPostingReportsBothTotals() {
		LedgerPosting posting = disbursement("16000000.00");

		assertThat(posting.totalDebit()).isEqualByComparingTo("16000000.00");
		assertThat(posting.totalCredit()).isEqualByComparingTo("16000000.00");
		assertThat(posting.refType()).isEqualTo(LedgerRefType.CONTRACT_ACTIVATION);
		assertThat(posting.refId()).isEqualTo(EVENT_ID);
		assertThat(posting.entryDate()).isEqualTo(ENTRY_DATE);
		assertThat(posting.isReversal()).isFalse();
	}

	@Test
	void aManyLinePostingBalancesWhenItsTotalsAgree() {
		LedgerPosting posting = LedgerPosting.of(LedgerRefType.PAYMENT, EVENT_ID, ENTRY_DATE, "Payment", List.of(
				LedgerPostingLine.debit(LedgerAccount.KAS, new BigDecimal("1000.00"), CONTRACT_ID),
				LedgerPostingLine.credit(LedgerAccount.PIUTANG_DENDA, new BigDecimal("100.00"), CONTRACT_ID),
				LedgerPostingLine.credit(LedgerAccount.PIUTANG_BUNGA, new BigDecimal("200.00"), CONTRACT_ID),
				LedgerPostingLine.credit(LedgerAccount.PIUTANG_POKOK, new BigDecimal("700.00"), CONTRACT_ID)));

		assertThat(posting.totalDebit()).isEqualByComparingTo(posting.totalCredit());
		assertThat(posting.totalDebit().scale()).isEqualTo(2);
	}

	@Test
	void totalsAreComparedNumericallyNotByScale() {
		LedgerPosting posting = LedgerPosting.of(LedgerRefType.PAYMENT, EVENT_ID, ENTRY_DATE, null, List.of(
				LedgerPostingLine.debit(LedgerAccount.KAS, new BigDecimal("100")),
				LedgerPostingLine.credit(LedgerAccount.PIUTANG_POKOK, new BigDecimal("100.00"))));

		assertThat(posting.totalDebit()).isEqualByComparingTo(posting.totalCredit());
	}

	@Test
	void anUnbalancedPostingIsRejected() {
		assertThatThrownBy(() -> LedgerPosting.of(LedgerRefType.PAYMENT, EVENT_ID, ENTRY_DATE, null, List.of(
				LedgerPostingLine.debit(LedgerAccount.KAS, new BigDecimal("100.00")),
				LedgerPostingLine.credit(LedgerAccount.PIUTANG_POKOK, new BigDecimal("99.99")))))
				.isInstanceOf(LedgerPostingException.class)
				.hasMessageContaining("unbalanced")
				.hasMessageContaining("99.99");
	}

	@Test
	void aPostingWithASingleLineIsRejected() {
		assertThatThrownBy(() -> LedgerPosting.of(LedgerRefType.PAYMENT, EVENT_ID, ENTRY_DATE, null,
				List.of(LedgerPostingLine.debit(LedgerAccount.KAS, new BigDecimal("100.00")))))
				.isInstanceOf(LedgerPostingException.class)
				.hasMessageContaining("at least 2 journal lines");
	}

	@Test
	void aPostingWithoutLinesIsRejected() {
		assertThatThrownBy(() -> LedgerPosting.of(LedgerRefType.PAYMENT, EVENT_ID, ENTRY_DATE, null, List.of()))
				.isInstanceOf(LedgerPostingException.class)
				.hasMessageContaining("at least 2 journal lines");
	}

	@Test
	void aNullLineIsRejected() {
		List<LedgerPostingLine> withHole = new ArrayList<>();
		withHole.add(LedgerPostingLine.debit(LedgerAccount.KAS, new BigDecimal("1.00")));
		withHole.add(null);

		assertThatThrownBy(() -> LedgerPosting.of(LedgerRefType.PAYMENT, EVENT_ID, ENTRY_DATE, null, withHole))
				.isInstanceOf(LedgerPostingException.class)
				.hasMessageContaining("null line");
	}

	@Test
	void linesAreDefensivelyCopied() {
		List<LedgerPostingLine> mutable = new ArrayList<>(List.of(
				LedgerPostingLine.debit(LedgerAccount.KAS, new BigDecimal("10.00")),
				LedgerPostingLine.credit(LedgerAccount.PIUTANG_POKOK, new BigDecimal("10.00"))));
		LedgerPosting posting = LedgerPosting.of(LedgerRefType.PAYMENT, EVENT_ID, ENTRY_DATE, null, mutable);

		mutable.clear();

		assertThat(posting.lines()).hasSize(2);
		assertThatThrownBy(() -> posting.lines().add(LedgerPostingLine.debit(LedgerAccount.KAS, BigDecimal.ONE)))
				.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void aReversalIsFlaggedAndKeepsTheOriginalEventReference() {
		UUID originalId = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
		LedgerPosting reversal = new LedgerPosting(LedgerRefType.PAYMENT, EVENT_ID, ENTRY_DATE, "Void payment",
				originalId, List.of(
						LedgerPostingLine.debit(LedgerAccount.PIUTANG_POKOK, new BigDecimal("10.00")),
						LedgerPostingLine.credit(LedgerAccount.KAS, new BigDecimal("10.00"))));

		assertThat(reversal.isReversal()).isTrue();
		assertThat(reversal.reversalOfId()).isEqualTo(originalId);
		// The reversed event keeps its reference, so both sides group under one (ref_type, ref_id).
		assertThat(reversal.refType()).isEqualTo(LedgerRefType.PAYMENT);
		assertThat(reversal.refId()).isEqualTo(EVENT_ID);
	}

	@Test
	void requiredReferencesAreEnforced() {
		List<LedgerPostingLine> lines = twoLines();

		assertThatThrownBy(() -> LedgerPosting.of(null, EVENT_ID, ENTRY_DATE, null, lines))
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> LedgerPosting.of(LedgerRefType.PAYMENT, null, ENTRY_DATE, null, lines))
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> LedgerPosting.of(LedgerRefType.PAYMENT, EVENT_ID, null, null, lines))
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> LedgerPosting.of(LedgerRefType.PAYMENT, EVENT_ID, ENTRY_DATE, null, null))
				.isInstanceOf(NullPointerException.class);
	}

	@Test
	void aBlankDescriptionBecomesNullAndARealOneIsTrimmed() {
		List<LedgerPostingLine> lines = twoLines();

		assertThat(LedgerPosting.of(LedgerRefType.PAYMENT, EVENT_ID, ENTRY_DATE, null, lines).description()).isNull();
		assertThat(LedgerPosting.of(LedgerRefType.PAYMENT, EVENT_ID, ENTRY_DATE, "   ", lines).description()).isNull();
		assertThat(LedgerPosting.of(LedgerRefType.PAYMENT, EVENT_ID, ENTRY_DATE, " Payment ", lines).description())
				.isEqualTo("Payment");
	}

	@Test
	void aDescriptionBeyondTheColumnWidthIsRejected() {
		List<LedgerPostingLine> lines = twoLines();

		assertThatThrownBy(() -> LedgerPosting.of(LedgerRefType.PAYMENT, EVENT_ID, ENTRY_DATE, "x".repeat(256), lines))
				.isInstanceOf(LedgerPostingException.class)
				.hasMessageContaining("at most 255 characters");
	}

	private static List<LedgerPostingLine> twoLines() {
		return List.of(LedgerPostingLine.debit(LedgerAccount.KAS, new BigDecimal("1.00")),
				LedgerPostingLine.credit(LedgerAccount.PIUTANG_POKOK, new BigDecimal("1.00")));
	}

	private static LedgerPosting disbursement(String principal) {
		return LedgerPosting.of(LedgerRefType.CONTRACT_ACTIVATION, EVENT_ID, ENTRY_DATE, "Disbursement", List.of(
				LedgerPostingLine.debit(LedgerAccount.PIUTANG_POKOK, new BigDecimal(principal), CONTRACT_ID),
				LedgerPostingLine.credit(LedgerAccount.KAS, new BigDecimal(principal), CONTRACT_ID)));
	}
}
