package com.serfira.settlement.domain;

import com.serfira.contract.domain.InterestScheme;
import com.serfira.contract.domain.schedule.ScheduleEngine;
import com.serfira.contract.domain.schedule.ScheduleLine;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T12 — settlement quote engine golden tests (ADR-018 D2/D6/D7). The Demo Contract B schedule is generated
 * from the pinned {@link ScheduleEngine} (so the expected component totals come from the amortization table,
 * not a hand transcription, exactly as ADR-018's worked example prescribes), and the engine's component
 * math and the D6 identities are asserted against it.
 *
 * <p>Scheme EFFECTIVE, principal 150,000,000, 36 months, 0.75%/month, start 2026-01-15 (Addendum §18.2).
 * The due date of period {@code n} is {@code start.plusMonths(n)}: period 1 is due 2026-02-15, period 6 is
 * due 2026-07-15, period 36 is due 2029-01-15. Period {@code n}'s accrual window runs from period
 * {@code n−1}'s due date (the contract start for period 1) to period {@code n}'s due date.
 */
class SettlementQuoteEngineTest {

	private static final BigDecimal REBATE_RATE = new BigDecimal("0.5000");
	private static final BigDecimal ADMIN_FEE = new BigDecimal("150000.00");
	private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);
	private static final LocalDate START = LocalDate.of(2026, 1, 15);

	private final List<ScheduleLine> schedule = new ScheduleEngine()
			.generate(new BigDecimal("150000000"), new BigDecimal("0.0075"), START, 36, InterestScheme.EFFECTIVE);

	/** Due date of 1-based period {@code n}: {@code start.plusMonths(n)}. */
	private LocalDate due(int period) {
		return START.plusMonths(period);
	}

	// -----------------------------------------------------------------------------------------------
	// On-a-due-date settlement: settle on period 6's due date (2026-07-15). Periods 1..5 paid; period 6
	// is billed that day and unpaid; periods 7..36 are future. The active period is 7 (window just opened,
	// 0 days elapsed), so accrued_interest = 0 (D7); future interest is periods 7..36 at full gross with a
	// 50% rebate.
	// -----------------------------------------------------------------------------------------------
	@Test
	void demoBOnDueDate() {
		LocalDate settlementDate = due(6); // 2026-07-15
		List<SettlementInstallmentInput> installments = new ArrayList<>();
		for (ScheduleLine line : schedule) {
			boolean paid = line.periodNo() <= 5;
			boolean billed = line.periodNo() <= 6; // period 6 billed on its due date
			BigDecimal recognizedInterest = billed ? line.interestAmount() : ZERO;
			BigDecimal resolvedBeyondPenalty = paid ? line.principalAmount().add(line.interestAmount()) : ZERO;
			installments.add(input(line, recognizedInterest, resolvedBeyondPenalty));
		}

		SettlementQuoteComponents c = SettlementQuoteEngine.price(new SettlementQuoteInput(
				settlementDate, START, REBATE_RATE, ADMIN_FEE, ZERO, installments));

		BigDecimal expectedPrincipal = sumPrincipal(6, 36);
		BigDecimal expectedBilledInterest = schedule.get(5).interestAmount(); // period 6 interest, unpaid
		BigDecimal expectedFutureGross = sumInterest(7, 36);
		BigDecimal expectedRebate = expectedFutureGross.multiply(REBATE_RATE).setScale(2, RoundingMode.HALF_EVEN);

		assertThat(c.accruedInterest()).isEqualByComparingTo("0.00");
		assertThat(c.outstandingPrincipal()).isEqualByComparingTo(expectedPrincipal);
		assertThat(c.unpaidBilledInterest()).isEqualByComparingTo(expectedBilledInterest);
		assertThat(c.penaltyOutstanding()).isEqualByComparingTo("0.00");
		assertThat(c.futureInterestGross()).isEqualByComparingTo(expectedFutureGross);
		assertThat(c.rebateAmount()).isEqualByComparingTo(expectedRebate);
		assertThat(c.futureInterestCharged()).isEqualByComparingTo(expectedFutureGross.subtract(expectedRebate));

		BigDecimal expectedGross = expectedPrincipal
				.add(expectedBilledInterest)
				.add(expectedFutureGross.subtract(expectedRebate))
				.add(ADMIN_FEE);
		assertThat(c.grossAmount()).isEqualByComparingTo(expectedGross);
		assertThat(c.cashDue()).isEqualByComparingTo(expectedGross); // credit_used = 0
		assertThat(c.availableCredit()).isEqualByComparingTo("0.00");
		assertThat(c.creditUsed()).isEqualByComparingTo("0.00");

		// futureInterestCharged reconstructs from gross (ADR-018 D6).
		BigDecimal reconstructed = c.grossAmount()
				.subtract(c.outstandingPrincipal())
				.subtract(c.unpaidBilledInterest())
				.subtract(c.accruedInterest())
				.subtract(c.penaltyOutstanding())
				.subtract(c.adminFee());
		assertThat(reconstructed).isEqualByComparingTo(c.futureInterestCharged());
	}

	// -----------------------------------------------------------------------------------------------
	// Mid-period settlement: 15 days into period 7's window (2026-07-15 .. 2026-08-15), on 2026-07-30.
	// Periods 1..6 paid and billed. Period 7 is the active period: NOT billed, due date in the future.
	// Its earned slice (ACT/30, 15/30) is accrued_interest with no rebate; the rest of period 7 plus
	// periods 8..36 is rebate-eligible gross — so period 7's interest is counted exactly once.
	// -----------------------------------------------------------------------------------------------
	@Test
	void demoBMidPeriodSplitsActivePeriodOnce() {
		LocalDate settlementDate = LocalDate.of(2026, 7, 30); // 15 days after 2026-07-15 (period 7 window start)
		List<SettlementInstallmentInput> installments = new ArrayList<>();
		for (ScheduleLine line : schedule) {
			boolean paid = line.periodNo() <= 6;
			boolean billed = line.periodNo() <= 6; // period 7 not yet due/billed on 2026-07-30
			BigDecimal recognizedInterest = billed ? line.interestAmount() : ZERO;
			BigDecimal resolvedBeyondPenalty = paid ? line.principalAmount().add(line.interestAmount()) : ZERO;
			installments.add(input(line, recognizedInterest, resolvedBeyondPenalty));
		}

		SettlementQuoteComponents c = SettlementQuoteEngine.price(new SettlementQuoteInput(
				settlementDate, START, REBATE_RATE, ADMIN_FEE, ZERO, installments));

		BigDecimal period7Interest = schedule.get(6).interestAmount();
		BigDecimal expectedAccrued = period7Interest
				.multiply(BigDecimal.valueOf(15))
				.divide(BigDecimal.valueOf(30), 2, RoundingMode.HALF_EVEN);
		// Future gross = all unrecognized interest (periods 7..36) minus the earned slice of period 7.
		BigDecimal expectedFutureGross = sumInterest(7, 36).subtract(expectedAccrued);
		BigDecimal expectedRebate = expectedFutureGross.multiply(REBATE_RATE).setScale(2, RoundingMode.HALF_EVEN);

		assertThat(c.accruedInterest()).isEqualByComparingTo(expectedAccrued);
		assertThat(c.unpaidBilledInterest()).isEqualByComparingTo("0.00"); // period 7 not billed yet
		assertThat(c.outstandingPrincipal()).isEqualByComparingTo(sumPrincipal(7, 36));
		assertThat(c.futureInterestGross()).isEqualByComparingTo(expectedFutureGross);
		assertThat(c.rebateAmount()).isEqualByComparingTo(expectedRebate);

		// The active period's interest is counted exactly once: earned slice + unearned remainder.
		BigDecimal unearnedRemainderOfPeriod7 = c.futureInterestGross().subtract(sumInterest(8, 36));
		assertThat(expectedAccrued.add(unearnedRemainderOfPeriod7)).isEqualByComparingTo(period7Interest);

		BigDecimal expectedGross = c.outstandingPrincipal()
				.add(c.unpaidBilledInterest())
				.add(expectedAccrued)
				.add(c.penaltyOutstanding())
				.add(expectedFutureGross.subtract(expectedRebate))
				.add(ADMIN_FEE);
		assertThat(c.grossAmount()).isEqualByComparingTo(expectedGross);
	}

	// -----------------------------------------------------------------------------------------------
	// Final-period settlement: all but period 36 paid and billed; no future interest, no rebate.
	// -----------------------------------------------------------------------------------------------
	@Test
	void finalPeriodHasNoFutureInterest() {
		LocalDate settlementDate = due(36); // 2029-01-15
		List<SettlementInstallmentInput> installments = new ArrayList<>();
		for (ScheduleLine line : schedule) {
			boolean paid = line.periodNo() <= 35;
			boolean billed = line.periodNo() <= 36;
			BigDecimal recognizedInterest = billed ? line.interestAmount() : ZERO;
			BigDecimal resolvedBeyondPenalty = paid ? line.principalAmount().add(line.interestAmount()) : ZERO;
			installments.add(input(line, recognizedInterest, resolvedBeyondPenalty));
		}

		SettlementQuoteComponents c = SettlementQuoteEngine.price(new SettlementQuoteInput(
				settlementDate, START, REBATE_RATE, ADMIN_FEE, ZERO, installments));

		assertThat(c.futureInterestGross()).isEqualByComparingTo("0.00");
		assertThat(c.rebateAmount()).isEqualByComparingTo("0.00");
		assertThat(c.accruedInterest()).isEqualByComparingTo("0.00");
		assertThat(c.outstandingPrincipal()).isEqualByComparingTo(schedule.get(35).principalAmount());
		assertThat(c.unpaidBilledInterest()).isEqualByComparingTo(schedule.get(35).interestAmount());
		BigDecimal expectedGross = schedule.get(35).principalAmount()
				.add(schedule.get(35).interestAmount())
				.add(ADMIN_FEE);
		assertThat(c.grossAmount()).isEqualByComparingTo(expectedGross);
	}

	@Test
	void act30CapsElapsedDaysAtThirty() {
		// One-period contract. Window is [start, due] = [2026-01-01, 2026-02-01]. Settle 2026-02-15, which
		// is 45 days after the window start and after the due date is NOT relevant — the active period is the
		// one with a future due date, so pick a far-future due date and a start 45 days before settlement.
		LocalDate start = LocalDate.of(2026, 1, 1);
		LocalDate due = LocalDate.of(2026, 3, 1); // due date in the future relative to settlement
		LocalDate settlementDate = LocalDate.of(2026, 2, 15); // 45 days after the window start (2026-01-01)
		BigDecimal interest = new BigDecimal("300.00");
		SettlementInstallmentInput only = new SettlementInstallmentInput(UUID.randomUUID(), 1, due, false,
				new BigDecimal("1000.00"), interest, ZERO, ZERO, ZERO);

		SettlementQuoteComponents c = SettlementQuoteEngine.price(new SettlementQuoteInput(
				settlementDate, start, REBATE_RATE, ADMIN_FEE, ZERO, List.of(only)));

		// 45 days elapsed -> capped to 30 -> full month's interest accrued; nothing left for future gross.
		assertThat(c.accruedInterest()).isEqualByComparingTo("300.00");
		assertThat(c.futureInterestGross()).isEqualByComparingTo("0.00");
	}

	@Test
	void settledAndWrittenOffInstallmentsAreExcluded() {
		SettlementInstallmentInput settled = new SettlementInstallmentInput(UUID.randomUUID(), 1, due(1), true,
				new BigDecimal("1000.00"), new BigDecimal("50.00"), new BigDecimal("50.00"),
				new BigDecimal("10.00"), new BigDecimal("1050.00"));

		SettlementQuoteComponents c = SettlementQuoteEngine.price(new SettlementQuoteInput(
				LocalDate.of(2026, 2, 1), START, REBATE_RATE, ADMIN_FEE, ZERO, List.of(settled)));

		assertThat(c.outstandingPrincipal()).isEqualByComparingTo("0.00");
		assertThat(c.unpaidBilledInterest()).isEqualByComparingTo("0.00");
		assertThat(c.penaltyOutstanding()).isEqualByComparingTo("0.00");
		assertThat(c.futureInterestGross()).isEqualByComparingTo("0.00");
		assertThat(c.grossAmount()).isEqualByComparingTo(ADMIN_FEE); // only the admin fee remains
	}

	private SettlementInstallmentInput input(ScheduleLine line, BigDecimal recognizedInterest,
			BigDecimal resolvedBeyondPenalty) {
		return new SettlementInstallmentInput(UUID.randomUUID(), line.periodNo(), line.dueDate(), false,
				line.principalAmount(), line.interestAmount(), recognizedInterest, ZERO, resolvedBeyondPenalty);
	}

	private BigDecimal sumPrincipal(int fromPeriod, int toPeriod) {
		return schedule.stream()
				.filter(l -> l.periodNo() >= fromPeriod && l.periodNo() <= toPeriod)
				.map(ScheduleLine::principalAmount)
				.reduce(BigDecimal.ZERO, BigDecimal::add)
				.setScale(2, RoundingMode.UNNECESSARY);
	}

	private BigDecimal sumInterest(int fromPeriod, int toPeriod) {
		return schedule.stream()
				.filter(l -> l.periodNo() >= fromPeriod && l.periodNo() <= toPeriod)
				.map(ScheduleLine::interestAmount)
				.reduce(BigDecimal.ZERO, BigDecimal::add)
				.setScale(2, RoundingMode.UNNECESSARY);
	}
}
