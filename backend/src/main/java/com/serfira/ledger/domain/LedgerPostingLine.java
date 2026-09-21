package com.serfira.ledger.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.UUID;

/**
 * One line of a {@link LedgerPosting}: exactly one of {@code debit}/{@code credit} is positive
 * (02_TECH_SPEC.md §3, V1 {@code ck_journal_line_not_both} / {@code ck_journal_line_not_zero}).
 *
 * <p>{@code contractId} is the contract the line belongs to when the event is contract-scoped; it is
 * what reconciliation "receivable vs installment" (Addendum §7.1, check C) groups by, and it stays
 * {@code null} for entries that are not tied to a single contract.
 *
 * <p>Amounts are scale-2 money, exactly as {@code NUMERIC(19,2)} stores them. Posting never rounds:
 * an amount that needs rounding is rejected, because rounding belongs to the calculation that produced
 * it (same rule as {@code InstallmentBalance}).
 */
public record LedgerPostingLine(LedgerAccount account, BigDecimal debit, BigDecimal credit, UUID contractId) {

	private static final int MONEY_SCALE = 2;

	public LedgerPostingLine {
		Objects.requireNonNull(account, "account");
		debit = money(debit, "debit");
		credit = money(credit, "credit");
		boolean hasDebit = debit.signum() > 0;
		boolean hasCredit = credit.signum() > 0;
		if (hasDebit == hasCredit) {
			throw new LedgerPostingException("journal line " + account.code()
					+ " must be exactly one of debit or credit (debit = " + debit + ", credit = " + credit + ")");
		}
	}

	public static LedgerPostingLine debit(LedgerAccount account, BigDecimal amount) {
		return debit(account, amount, null);
	}

	public static LedgerPostingLine debit(LedgerAccount account, BigDecimal amount, UUID contractId) {
		return new LedgerPostingLine(account, amount, BigDecimal.ZERO, contractId);
	}

	public static LedgerPostingLine credit(LedgerAccount account, BigDecimal amount) {
		return credit(account, amount, null);
	}

	public static LedgerPostingLine credit(LedgerAccount account, BigDecimal amount, UUID contractId) {
		return new LedgerPostingLine(account, BigDecimal.ZERO, amount, contractId);
	}

	private static BigDecimal money(BigDecimal amount, String side) {
		Objects.requireNonNull(amount, side);
		if (amount.signum() < 0) {
			throw new LedgerPostingException("journal line " + side + " must be >= 0 but was " + amount);
		}
		try {
			return amount.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
		} catch (ArithmeticException ex) {
			throw new LedgerPostingException("journal line " + side + " must be a money amount with at most "
					+ MONEY_SCALE + " decimal places but was " + amount);
		}
	}
}
