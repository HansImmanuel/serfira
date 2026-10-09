package com.serfira.settlement.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * Prices an early-settlement quote (E1, task T12, ADR-018 D2/D6/D7). Pure: no Spring, JPA or Clock — the
 * business date and config constants are passed in, so the whole quote math is unit-testable against
 * pinned golden values (ADR-018 worked example, Demo Contract B).
 *
 * <p>Components (ADR-018 D6), all scale-2 HALF_EVEN:
 *
 * <pre>
 * outstanding_principal  = Σ unpaid principal (incl. future periods)
 * unpaid_billed_interest = Σ recognized-but-unpaid interest (real PIUTANG_BUNGA)
 * accrued_interest       = running interest of the single earliest active period (ACT/30, D7)
 * penalty_outstanding    = Σ effective remaining penalty (invariant 9, D8)
 * futureInterestGross    = Σ (interestAmount − recognizedInterest) over unbilled periods − accrued_interest
 * rebate_amount          = round(rebateRate × futureInterestGross, HALF_EVEN, 2)
 * futureInterestCharged  = futureInterestGross − rebate_amount
 * gross_amount           = outstanding_principal + unpaid_billed_interest + accrued_interest
 *                          + penalty_outstanding + futureInterestCharged + admin_fee
 * cash_due               = gross_amount − credit_used    (credit_used = 0 at T12)
 * </pre>
 *
 * <p>The active period (D7) is the single earliest installment whose interest is not yet fully recognized
 * and whose due date is strictly after the settlement date. Its earned slice is {@code accrued_interest}
 * (charged in full, no rebate); its unearned remainder stays in {@code futureInterestGross} because the
 * gross subtracts the earned slice (D2's active-period boundary). An installment already billed on or
 * before the settlement date contributes zero to {@code accrued_interest} and zero to
 * {@code futureInterestGross} (its {@code interestAmount − recognizedInterest} is 0).
 */
public final class SettlementQuoteEngine {

	private static final int MONEY_SCALE = 2;
	private static final MathContext HIGH_PRECISION = MathContext.DECIMAL128;
	private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(MONEY_SCALE);
	private static final BigDecimal ACT_30_PERIOD_DAYS = BigDecimal.valueOf(30);
	private static final long MAX_ELAPSED_DAYS = 30L;

	private SettlementQuoteEngine() {
	}

	/**
	 * Prices the quote.
	 *
	 * @param input the business date, config constants and per-installment amounts
	 * @return the priced components (ADR-018 D6)
	 * @throws NullPointerException     if {@code input} or any required field is null
	 * @throws IllegalArgumentException if a monetary input is finer than scale 2, or a config value is
	 *                                  negative
	 */
	public static SettlementQuoteComponents price(SettlementQuoteInput input) {
		Objects.requireNonNull(input, "input");
		Objects.requireNonNull(input.settlementDate(), "settlementDate");
		Objects.requireNonNull(input.contractStartDate(), "contractStartDate");
		BigDecimal rebateRate = requireNonNegative(input.rebateRate(), "rebateRate");
		BigDecimal adminFee = requireMoney(input.adminFee(), "adminFee");
		BigDecimal availableCredit = requireMoney(input.availableCredit(), "availableCredit");

		BigDecimal outstandingPrincipal = ZERO;
		BigDecimal unpaidBilledInterest = ZERO;
		BigDecimal futureInterestGrossRaw = ZERO;
		BigDecimal penaltyOutstanding = ZERO;

		LocalDate activePeriodStart = input.contractStartDate();
		boolean activePeriodFound = false;
		BigDecimal accruedInterest = ZERO;

		for (SettlementInstallmentInput installment : input.installments()) {
			LocalDate periodStart = activePeriodStart;
			// Advance the window boundary for the next iteration: this installment's due date is the start
			// of the following period's accrual window.
			activePeriodStart = installment.dueDate();

			if (installment.settled()) {
				// SETTLED / WRITTEN_OFF: receivable resolved another way, excluded from pricing (D2).
				continue;
			}

			BigDecimal principal = requireMoney(installment.principalAmount(), "principalAmount");
			BigDecimal scheduledInterest = requireMoney(installment.interestAmount(), "interestAmount");
			BigDecimal recognizedInterest = requireMoney(installment.recognizedInterest(), "recognizedInterest");
			BigDecimal effectivePenalty = requireMoney(installment.effectivePenalty(), "effectivePenalty");
			BigDecimal resolvedBeyondPenalty = requireMoney(installment.resolvedBeyondPenalty(), "resolvedBeyondPenalty");

			penaltyOutstanding = penaltyOutstanding.add(effectivePenalty);

			// Waterfall order (ADR-009): what is already resolved beyond penalty covers interest first,
			// then principal.
			BigDecimal unpaidInterest = max0(recognizedInterest.subtract(resolvedBeyondPenalty));
			BigDecimal resolvedBeyondInterest = max0(resolvedBeyondPenalty.subtract(recognizedInterest));
			BigDecimal unpaidPrincipal = max0(principal.subtract(resolvedBeyondInterest));

			unpaidBilledInterest = unpaidBilledInterest.add(unpaidInterest);
			outstandingPrincipal = outstandingPrincipal.add(unpaidPrincipal);

			BigDecimal unrecognizedInterest = max0(scheduledInterest.subtract(recognizedInterest));
			if (unrecognizedInterest.signum() == 0) {
				// Already fully billed: no future interest, no accrual (D2/D7).
				continue;
			}

			// Future / active period. The earliest unbilled period with a future due date is the active
			// period; its earned slice is accrued_interest (D7), the rest is rebate-eligible gross (D2).
			futureInterestGrossRaw = futureInterestGrossRaw.add(unrecognizedInterest);
			if (!activePeriodFound && installment.dueDate().isAfter(input.settlementDate())) {
				activePeriodFound = true;
				accruedInterest = runningInterest(scheduledInterest, periodStart, input.settlementDate());
			}
		}

		BigDecimal futureInterestGross = max0(futureInterestGrossRaw.subtract(accruedInterest));
		BigDecimal rebateAmount = roundHalfEven(rebateRate.multiply(futureInterestGross, HIGH_PRECISION));
		if (rebateAmount.compareTo(futureInterestGross) > 0) {
			// A rebate rate > 1 is a misconfiguration; never charge a negative future-interest amount.
			rebateAmount = futureInterestGross;
		}
		BigDecimal futureInterestCharged = futureInterestGross.subtract(rebateAmount);

		BigDecimal grossAmount = outstandingPrincipal
				.add(unpaidBilledInterest)
				.add(accruedInterest)
				.add(penaltyOutstanding)
				.add(futureInterestCharged)
				.add(adminFee);
		BigDecimal creditUsed = ZERO;
		BigDecimal cashDue = grossAmount.subtract(creditUsed);

		return new SettlementQuoteComponents(outstandingPrincipal, unpaidBilledInterest, accruedInterest,
				penaltyOutstanding, futureInterestGross, rebateAmount, futureInterestCharged, adminFee,
				availableCredit, creditUsed, grossAmount, cashDue);
	}

	/**
	 * Running interest of the active period (ADR-018 D7, Addendum §8): {@code scheduledInterest ×
	 * min(daysElapsed, 30) / 30}, HALF_EVEN scale 2. {@code daysElapsed} is from the period start
	 * (previous due date, or the contract start for period 1) to the settlement date, floored at 0.
	 */
	private static BigDecimal runningInterest(BigDecimal scheduledInterest, LocalDate periodStart,
			LocalDate settlementDate) {
		long elapsed = ChronoUnit.DAYS.between(periodStart, settlementDate);
		if (elapsed < 0) {
			elapsed = 0;
		}
		if (elapsed > MAX_ELAPSED_DAYS) {
			elapsed = MAX_ELAPSED_DAYS;
		}
		return roundHalfEven(scheduledInterest
				.multiply(BigDecimal.valueOf(elapsed), HIGH_PRECISION)
				.divide(ACT_30_PERIOD_DAYS, HIGH_PRECISION));
	}

	private static BigDecimal max0(BigDecimal value) {
		return value.signum() > 0 ? value : ZERO;
	}

	private static BigDecimal roundHalfEven(BigDecimal value) {
		return value.setScale(MONEY_SCALE, RoundingMode.HALF_EVEN);
	}

	private static BigDecimal requireMoney(BigDecimal value, String name) {
		Objects.requireNonNull(value, name);
		if (value.scale() > MONEY_SCALE) {
			throw new IllegalArgumentException(name + " must be money with at most " + MONEY_SCALE
					+ " decimal places but was " + value);
		}
		if (value.signum() < 0) {
			throw new IllegalArgumentException(name + " must be >= 0 but was " + value);
		}
		return value;
	}

	private static BigDecimal requireNonNegative(BigDecimal value, String name) {
		Objects.requireNonNull(value, name);
		if (value.signum() < 0) {
			throw new IllegalArgumentException(name + " must be >= 0 but was " + value);
		}
		return value;
	}
}
