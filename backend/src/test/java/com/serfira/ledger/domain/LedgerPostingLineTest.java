package com.serfira.ledger.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pure unit tests for the line-level rules of a journal entry (ADR-008, V1 {@code ck_journal_line_*}). */
class LedgerPostingLineTest {

	private static final UUID CONTRACT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

	@Test
	void debitLineKeepsOnlyTheDebitSide() {
		LedgerPostingLine line = LedgerPostingLine.debit(LedgerAccount.KAS, new BigDecimal("500.00"), CONTRACT_ID);

		assertThat(line.account()).isEqualTo(LedgerAccount.KAS);
		assertThat(line.debit()).isEqualByComparingTo("500.00");
		assertThat(line.credit()).isEqualByComparingTo("0.00");
		assertThat(line.contractId()).isEqualTo(CONTRACT_ID);
	}

	@Test
	void creditLineKeepsOnlyTheCreditSide() {
		LedgerPostingLine line = LedgerPostingLine.credit(LedgerAccount.PIUTANG_POKOK, new BigDecimal("500.00"));

		assertThat(line.debit()).isEqualByComparingTo("0.00");
		assertThat(line.credit()).isEqualByComparingTo("500.00");
		// contract_id is optional: not every entry belongs to a single contract.
		assertThat(line.contractId()).isNull();
	}

	@Test
	void amountsAreNormalizedToTheMoneyScale() {
		LedgerPostingLine line = LedgerPostingLine.credit(LedgerAccount.KAS, new BigDecimal("500"));

		assertThat(line.credit()).isEqualByComparingTo("500.00");
		assertThat(line.credit().scale()).isEqualTo(2);
	}

	@Test
	void aLineWithBothSidesIsRejected() {
		assertThatThrownBy(() -> new LedgerPostingLine(LedgerAccount.KAS, new BigDecimal("1.00"),
				new BigDecimal("1.00"), null))
				.isInstanceOf(LedgerPostingException.class)
				.hasMessageContaining("exactly one of debit or credit");
	}

	@Test
	void aLineWithNeitherSideIsRejected() {
		assertThatThrownBy(() -> new LedgerPostingLine(LedgerAccount.KAS, BigDecimal.ZERO, new BigDecimal("0.00"), null))
				.isInstanceOf(LedgerPostingException.class)
				.hasMessageContaining("exactly one of debit or credit");
	}

	@Test
	void aNegativeAmountIsRejected() {
		assertThatThrownBy(() -> LedgerPostingLine.debit(LedgerAccount.KAS, new BigDecimal("-1.00")))
				.isInstanceOf(LedgerPostingException.class)
				.hasMessageContaining("must be >= 0");
	}

	@Test
	void anAmountBelowCentPrecisionIsRejectedInsteadOfBeingRounded() {
		assertThatThrownBy(() -> LedgerPostingLine.debit(LedgerAccount.KAS, new BigDecimal("0.005")))
				.isInstanceOf(LedgerPostingException.class)
				.hasMessageContaining("at most 2 decimal places");
	}

	@Test
	void aMissingAmountIsRejected() {
		assertThatThrownBy(() -> LedgerPostingLine.debit(LedgerAccount.KAS, null))
				.isInstanceOf(NullPointerException.class);
	}

	@Test
	void aMissingAccountIsRejected() {
		assertThatThrownBy(() -> LedgerPostingLine.debit(null, new BigDecimal("1.00")))
				.isInstanceOf(NullPointerException.class);
	}
}
