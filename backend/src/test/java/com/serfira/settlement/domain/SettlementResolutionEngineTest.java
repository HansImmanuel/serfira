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
 * T13 — settlement receivable-resolution engine golden tests (ADR-018 D5). The resolution totals per
 * component must equal the quote engine's {@code penalty_outstanding} / {@code unpaid_billed_interest} /
 * {@code outstanding_principal} components, since both derive from the same inputs — this is the cross-check
 * of invariant 1 and D5. The shared Demo Contract B schedule is generated from the pinned
 * {@link ScheduleEngine} exactly as {@code SettlementQuoteEngineTest} does.
 *
 * <p>Scheme EFFECTIVE, principal 150,000,000, 36 months, 0.75%/month, start 2026-01-15 (Addendum §18.2).
 */
class SettlementResolutionEngineTest {

	private static final BigDecimal REBATE_RATE = new BigDecimal("0.5000");
	private static final BigDecimal ADMIN_FEE = new BigDecimal("150000.00");
	private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);
	private static final LocalDate START = LocalDate.of(2026, 1, 15);

	private final List<ScheduleLine> schedule = new ScheduleEngine()
			.generate(new BigDecimal("150000000"), new BigDecimal("0.0075"), START, 36, InterestScheme.EFFECTIVE);

	private LocalDate due(int period) {
		return START.plusMonths(period);
	}

	@Test
	void resolutionTotalsMatchQuoteComponentsOnTheDueDate() {
		// Settle on period 6's due date (2026-07-15): periods 1..5 paid; period 6 billed and unpaid; periods
		// 7..36 future. Future interest is never a resolution line (it is not a receivable).
		List<SettlementInstallmentInput> installments = installments(6, 5);

		SettlementQuoteComponents quote = SettlementQuoteEngine.price(new SettlementQuoteInput(
				due(6), START, REBATE_RATE, ADMIN_FEE, ZERO, installments));
		List<SettlementResolutionLine> resolution = SettlementResolutionEngine.resolve(installments);

		assertThat(sum(resolution, SettlementAllocationType.PRINCIPAL))
				.isEqualByComparingTo(quote.outstandingPrincipal());
		assertThat(sum(resolution, SettlementAllocationType.INTEREST))
				.isEqualByComparingTo(quote.unpaidBilledInterest());
		assertThat(sum(resolution, SettlementAllocationType.PENALTY))
				.isEqualByComparingTo(quote.penaltyOutstanding());

		// Only period 6's INTEREST is billed-but-unpaid; principals of 6..36 are unpaid. No PENALTY lines
		// (no late periods). Every line is strictly positive.
		assertThat(resolution).allMatch(line -> line.amount().signum() > 0);
	}

	@Test
	void linesAreOldestDueFirstAndPenaltyInterestPrincipalWithinAnInstallment() {
		// Give period 6 a penalty so an installment has all three components; settle on its due date.
		List<SettlementInstallmentInput> installments = new ArrayList<>();
		for (ScheduleLine line : schedule) {
			boolean paid = line.periodNo() <= 5;
			boolean billed = line.periodNo() <= 6;
			BigDecimal recognizedInterest = billed ? line.interestAmount() : ZERO;
			BigDecimal resolvedBeyondPenalty = paid ? line.principalAmount().add(line.interestAmount()) : ZERO;
			BigDecimal effectivePenalty = line.periodNo() == 6 ? new BigDecimal("1234.00") : ZERO;
			installments.add(new SettlementInstallmentInput(UUID.randomUUID(), line.periodNo(), line.dueDate(),
					false, line.principalAmount(), line.interestAmount(), recognizedInterest, effectivePenalty,
					resolvedBeyondPenalty));
		}

		List<SettlementResolutionLine> resolution = SettlementResolutionEngine.resolve(installments);

		// The first installment with any outstanding is period 6 (periods 1..5 fully paid), and its lines
		// appear in PENALTY, INTEREST, PRINCIPAL order.
		assertThat(resolution.get(0).type()).isEqualTo(SettlementAllocationType.PENALTY);
		assertThat(resolution.get(1).type()).isEqualTo(SettlementAllocationType.INTEREST);
		assertThat(resolution.get(2).type()).isEqualTo(SettlementAllocationType.PRINCIPAL);
		UUID firstInstallment = resolution.get(0).installmentId();
		assertThat(resolution.get(1).installmentId()).isEqualTo(firstInstallment);
		assertThat(resolution.get(2).installmentId()).isEqualTo(firstInstallment);
	}

	@Test
	void settledAndWrittenOffInstallmentsProduceNoLines() {
		SettlementInstallmentInput settled = new SettlementInstallmentInput(UUID.randomUUID(), 1, due(1), true,
				new BigDecimal("1000.00"), new BigDecimal("50.00"), new BigDecimal("50.00"),
				new BigDecimal("10.00"), new BigDecimal("1050.00"));

		assertThat(SettlementResolutionEngine.resolve(List.of(settled))).isEmpty();
	}

	@Test
	void aFullyPaidInstallmentProducesNoLines() {
		ScheduleLine first = schedule.get(0);
		SettlementInstallmentInput paid = new SettlementInstallmentInput(UUID.randomUUID(), first.periodNo(),
				first.dueDate(), false, first.principalAmount(), first.interestAmount(), first.interestAmount(),
				ZERO, first.principalAmount().add(first.interestAmount()));

		assertThat(SettlementResolutionEngine.resolve(List.of(paid))).isEmpty();
	}

	private List<SettlementInstallmentInput> installments(int billedThrough, int paidThrough) {
		List<SettlementInstallmentInput> installments = new ArrayList<>();
		for (ScheduleLine line : schedule) {
			boolean paid = line.periodNo() <= paidThrough;
			boolean billed = line.periodNo() <= billedThrough;
			BigDecimal recognizedInterest = billed ? line.interestAmount() : ZERO;
			BigDecimal resolvedBeyondPenalty = paid ? line.principalAmount().add(line.interestAmount()) : ZERO;
			installments.add(new SettlementInstallmentInput(UUID.randomUUID(), line.periodNo(), line.dueDate(),
					false, line.principalAmount(), line.interestAmount(), recognizedInterest, ZERO,
					resolvedBeyondPenalty));
		}
		return installments;
	}

	private BigDecimal sum(List<SettlementResolutionLine> resolution, SettlementAllocationType type) {
		return resolution.stream()
				.filter(line -> line.type() == type)
				.map(SettlementResolutionLine::amount)
				.reduce(BigDecimal.ZERO, BigDecimal::add)
				.setScale(2, RoundingMode.UNNECESSARY);
	}
}
