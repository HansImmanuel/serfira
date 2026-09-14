package com.serfira.contract.domain.schedule;

import com.serfira.contract.domain.InterestScheme;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Pure calculation engine for repayment schedules (PRD §3, TECH SPEC §4.1/§4.2, stories B2/B3).
 *
 * <p>Financial rules implemented here (TECH SPEC §2.1):
 * <ul>
 *   <li>Money is always {@link BigDecimal}; rates are monthly decimal fractions (1.5% = {@code 0.0150}).</li>
 *   <li>Non-final rounding uses {@link RoundingMode#HALF_EVEN} (banker's rounding) at scale 2.</li>
 *   <li><b>FLAT:</b> periodic principal = plafon/tenor, periodic interest = (plafon × rate × tenor)/tenor;
 *       both are rounded to scale 2 for periods 1..n−1 and the last period absorbs the rounding residuals,
 *       so Σprincipal = plafon and Σinterest = bungaTotal exactly.</li>
 *   <li><b>EFFECTIVE:</b> the constant payment {@code A = P·i·(1+i)ⁿ / ((1+i)ⁿ − 1)} is computed at
 *       34-digit precision (then rounded to scale 2). Each period 1..n−1 records interest =
 *       round(remaining × i) and principal = A − interest; the last period absorbs the remaining principal.</li>
 * </ul>
 */
public final class ScheduleEngine {

	private static final int MONEY_SCALE = 2;
	private static final MathContext HIGH_PRECISION = MathContext.DECIMAL128;
	private static final BigDecimal ONE = BigDecimal.ONE;

	/**
	 * Generates the full schedule of {@code tenorMonths} installments.
	 *
	 * @throws NullPointerException     if {@code principal}, {@code monthlyRate}, {@code startDate} or
	 *                                  {@code scheme} is {@code null}
	 * @throws IllegalArgumentException if {@code principal <= 0}, {@code monthlyRate < 0} or
	 *                                  {@code tenorMonths < 1}
	 */
	public List<ScheduleLine> generate(BigDecimal principal, BigDecimal monthlyRate, LocalDate startDate,
			int tenorMonths, InterestScheme scheme) {
		Objects.requireNonNull(scheme, "scheme");
		Objects.requireNonNull(principal, "principal");
		Objects.requireNonNull(monthlyRate, "monthlyRate");
		Objects.requireNonNull(startDate, "startDate");
		if (principal.signum() <= 0) {
			throw new IllegalArgumentException("principal must be > 0 but was " + principal);
		}
		if (monthlyRate.signum() < 0) {
			throw new IllegalArgumentException("monthlyRate must be >= 0 but was " + monthlyRate);
		}
		if (tenorMonths < 1) {
			throw new IllegalArgumentException("tenorMonths must be >= 1 but was " + tenorMonths);
		}

		return switch (scheme) {
			case FLAT -> flatSchedule(principal, monthlyRate, startDate, tenorMonths);
			case EFFECTIVE -> effectiveSchedule(principal, monthlyRate, startDate, tenorMonths);
		};
	}

	/**
	 * FLAT: same principal and interest for periods 1..n−1; the last period absorbs both rounding residuals.
	 */
	private List<ScheduleLine> flatSchedule(BigDecimal principal, BigDecimal monthlyRate, LocalDate startDate,
			int tenorMonths) {
		BigDecimal totalInterest = roundHalfEven(
				principal.multiply(monthlyRate).multiply(BigDecimal.valueOf(tenorMonths)));
		BigDecimal principalPerPeriod = roundHalfEven(
				principal.divide(BigDecimal.valueOf(tenorMonths), HIGH_PRECISION));
		BigDecimal interestPerPeriod = roundHalfEven(
				totalInterest.divide(BigDecimal.valueOf(tenorMonths), HIGH_PRECISION));

		List<ScheduleLine> schedule = new ArrayList<>(tenorMonths);
		BigDecimal principalSoFar = BigDecimal.ZERO;
		BigDecimal interestSoFar = BigDecimal.ZERO;
		for (int period = 1; period <= tenorMonths; period++) {
			boolean last = period == tenorMonths;
			BigDecimal principalAmount = last ? principal.subtract(principalSoFar) : principalPerPeriod;
			BigDecimal interestAmount = last ? totalInterest.subtract(interestSoFar) : interestPerPeriod;
			principalSoFar = principalSoFar.add(principalAmount);
			interestSoFar = interestSoFar.add(interestAmount);
			schedule.add(new ScheduleLine(period, ScheduleDueDate.of(startDate, period), principalAmount, interestAmount));
		}
		return schedule;
	}

	/**
	 * EFFECTIVE: annuity payment computed at 34-digit precision; the last period absorbs the remaining
	 * principal so Σprincipal = plafon exactly.
	 */
	private List<ScheduleLine> effectiveSchedule(BigDecimal principal, BigDecimal monthlyRate, LocalDate startDate,
			int tenorMonths) {
		BigDecimal payment;
		if (monthlyRate.signum() == 0) {
			payment = roundHalfEven(principal.divide(BigDecimal.valueOf(tenorMonths), HIGH_PRECISION));
		} else {
			BigDecimal compound;
			try {
				compound = ONE.add(monthlyRate).pow(tenorMonths, HIGH_PRECISION);
			} catch (ArithmeticException overflow) {
				throw new IllegalArgumentException(
						"rate/tenor combination overflows decimal128 precision: " + monthlyRate + " x " + tenorMonths,
						overflow);
			}
			payment = roundHalfEven(principal.multiply(monthlyRate).multiply(compound)
					.divide(compound.subtract(ONE), HIGH_PRECISION));
		}

		List<ScheduleLine> schedule = new ArrayList<>(tenorMonths);
		BigDecimal outstanding = principal;
		for (int period = 1; period <= tenorMonths; period++) {
			BigDecimal interest = roundHalfEven(outstanding.multiply(monthlyRate));
			BigDecimal principalAmount = (period == tenorMonths) ? outstanding : payment.subtract(interest);
			outstanding = outstanding.subtract(principalAmount);
			schedule.add(new ScheduleLine(period, ScheduleDueDate.of(startDate, period), principalAmount, interest));
		}
		return schedule;
	}

	private static BigDecimal roundHalfEven(BigDecimal value) {
		return value.setScale(MONEY_SCALE, RoundingMode.HALF_EVEN);
	}
}