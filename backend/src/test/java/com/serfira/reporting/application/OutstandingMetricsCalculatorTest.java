package com.serfira.reporting.application;

import com.serfira.contract.application.CohortReceivableSnapshot;
import com.serfira.contract.application.InstallmentAgingSnapshot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T36 — the ADR-026 metric definitions on hand-computed fixtures. The calculator only aggregates: outstanding,
 * DPD and the recognized total arrive already computed by the {@code contract} module, and the collected sum
 * arrives already filtered by the {@code payment} module (POSTED, non-EXCESS, cohort only).
 */
class OutstandingMetricsCalculatorTest {

	private static final LocalDate AS_OF = LocalDate.of(2026, 3, 31);
	private static final String CONTRACT_NO = "MF-202601-0001";

	@Test
	void oneInstallmentOver90DaysPutsTheWholeContractIntoNpl() {
		UUID late = UUID.randomUUID();
		UUID performing = UUID.randomUUID();
		List<InstallmentAgingSnapshot> owed = List.of(
				owed(late, AS_OF.minusDays(94), 91, "100.00"),      // > 90: the contract is NPL
				owed(late, AS_OF.plusDays(30), 0, "200.00"),        // future-due principal still counts
				owed(late, AS_OF.plusDays(61), 0, "300.00"),
				owed(performing, AS_OF.minusDays(10), 7, "400.00"));

		OutstandingMetrics metrics = calculate(owed, List.of(), "0.00");

		assertThat(metrics.totalOutstanding()).isEqualByComparingTo("1000.00");
		assertThat(metrics.nplContractCount()).isEqualTo(1);
		assertThat(metrics.nplOutstanding()).isEqualByComparingTo("600.00"); // 100 + 200 + 300, not only the bucket
		assertThat(metrics.nplRatio()).isEqualTo(new BigDecimal("0.6000"));
		assertThat(metrics.activeContractCount()).isEqualTo(2);
	}

	@Test
	void dpd90IsNotNplButDpd91Is() {
		UUID atNinety = UUID.randomUUID();
		UUID atNinetyOne = UUID.randomUUID();
		List<InstallmentAgingSnapshot> owed = List.of(
				owed(atNinety, AS_OF.minusDays(93), 90, "500.00"),
				owed(atNinetyOne, AS_OF.minusDays(94), 91, "700.00"));

		OutstandingMetrics metrics = calculate(owed, List.of(), "0.00");

		assertThat(metrics.nplContractCount()).isEqualTo(1);
		assertThat(metrics.nplOutstanding()).isEqualByComparingTo("700.00");
	}

	@Test
	void accountsReceivableKeepsInstallmentsDueTodayInsideGraceAndDropsTomorrows() {
		UUID contract = UUID.randomUUID();
		List<InstallmentAgingSnapshot> owed = List.of(
				owed(contract, AS_OF, 0, "100.00"),                 // due today, inside grace: DPD 0, still AR
				owed(contract, AS_OF.minusDays(2), 0, "200.00"),    // due 2 days ago, still inside a 3-day grace
				owed(contract, AS_OF.minusDays(20), 17, "300.00"),  // overdue
				owed(contract, AS_OF.plusDays(1), 0, "400.00"));    // due tomorrow: not receivable yet

		OutstandingMetrics metrics = calculate(owed, List.of(), "0.00");

		assertThat(metrics.accountsReceivable()).isEqualByComparingTo("600.00");
		assertThat(metrics.totalOutstanding()).isEqualByComparingTo("1000.00");
		assertThat(metrics.accountsReceivable()).isLessThanOrEqualTo(metrics.totalOutstanding());
	}

	@Test
	void settlementLeavesBothSidesOfTheCollectionRateAndWriteOffStaysInDue() {
		List<CohortReceivableSnapshot> cohort = List.of(
				cohort("1573333.33", "0.00"),       // paid on time (counted in collected)
				cohort("1573333.33", "0.00"),       // partly paid
				cohort("1573333.33", "1573333.33"), // settled: out of due, and settlement is never in collected
				cohort("1573333.33", "0.00"));      // written off: recognized, never collected, stays in due
		// Collected arrives from the payment port: 1,573,333.33 on the first + 500,000.00 on the second.
		OutstandingMetrics metrics = calculate(List.of(), cohort, "2073333.33");

		assertThat(metrics.due()).isEqualByComparingTo("4719999.99");
		assertThat(metrics.collected()).isEqualByComparingTo("2073333.33");
		// 2,073,333.33 / 4,719,999.99 = 0.43926... -> 0.4393
		assertThat(metrics.collectionRate()).isEqualTo(new BigDecimal("0.4393"));
		assertThat(metrics.collected()).isLessThanOrEqualTo(metrics.due());
	}

	@Test
	void theCollectionPeriodRunsFromTheFirstOfTheMonthToAsOf() {
		OutstandingMetrics metrics = calculate(List.of(), List.of(), "0.00");

		assertThat(metrics.periodFrom()).isEqualTo(LocalDate.of(2026, 3, 1));
		assertThat(metrics.periodTo()).isEqualTo(AS_OF);
		assertThat(OutstandingMetricsCalculator.periodFrom(LocalDate.of(2026, 3, 1))).isEqualTo(LocalDate.of(2026, 3, 1));
	}

	@Test
	void zeroDenominatorsGiveNullRatiosAndZeroMoney() {
		OutstandingMetrics metrics = calculate(List.of(), List.of(), "0.00");

		assertThat(metrics.totalOutstanding()).isEqualTo(new BigDecimal("0.00"));
		assertThat(metrics.accountsReceivable()).isEqualTo(new BigDecimal("0.00"));
		assertThat(metrics.nplOutstanding()).isEqualTo(new BigDecimal("0.00"));
		assertThat(metrics.nplRatio()).isNull();
		assertThat(metrics.due()).isEqualTo(new BigDecimal("0.00"));
		assertThat(metrics.collectionRate()).isNull();
		assertThat(metrics.settlementCash()).isEqualTo(new BigDecimal("0.00"));
		assertThat(metrics.activeContractCount()).isZero();
	}

	@Test
	void aFullySettledCohortHasZeroDueAndANullRate() {
		OutstandingMetrics metrics = calculate(List.of(), List.of(cohort("100.00", "100.00")), "0.00");

		assertThat(metrics.due()).isEqualByComparingTo("0.00");
		assertThat(metrics.collectionRate()).isNull();
	}

	@Test
	void ratiosAreScale4HalfUp() {
		// 1 / 32 = 0.03125: HALF_UP gives 0.0313 (HALF_EVEN would give 0.0312).
		assertThat(OutstandingMetricsCalculator.ratio(new BigDecimal("1.00"), new BigDecimal("32.00")))
				.isEqualTo(new BigDecimal("0.0313"));
		assertThat(OutstandingMetricsCalculator.ratio(new BigDecimal("2.00"), new BigDecimal("3.00")))
				.isEqualTo(new BigDecimal("0.6667"));
		assertThat(OutstandingMetricsCalculator.ratio(new BigDecimal("5.00"), new BigDecimal("5.00")))
				.isEqualTo(new BigDecimal("1.0000"));
		assertThat(OutstandingMetricsCalculator.ratio(new BigDecimal("0.00"), new BigDecimal("5.00")))
				.isEqualTo(new BigDecimal("0.0000"));
	}

	@Test
	void settlementCashIsPassedThroughAsScale2Money() {
		OutstandingMetrics metrics = OutstandingMetricsCalculator.calculate(AS_OF, List.of(), List.of(),
				BigDecimal.ZERO, new BigDecimal("125000000"));

		assertThat(metrics.settlementCash()).isEqualTo(new BigDecimal("125000000.00"));
		assertThat(metrics.collected()).isEqualTo(new BigDecimal("0.00"));
	}

	private static OutstandingMetrics calculate(List<InstallmentAgingSnapshot> owed,
			List<CohortReceivableSnapshot> cohort, String collected) {
		return OutstandingMetricsCalculator.calculate(AS_OF, owed, cohort, new BigDecimal(collected),
				new BigDecimal("0.00"));
	}

	private static InstallmentAgingSnapshot owed(UUID contractId, LocalDate dueDate, int daysPastDue,
			String outstanding) {
		return new InstallmentAgingSnapshot(contractId, CONTRACT_NO, dueDate, daysPastDue, new BigDecimal(outstanding));
	}

	private static CohortReceivableSnapshot cohort(String recognizedTotal, String settled) {
		return new CohortReceivableSnapshot(UUID.randomUUID(), new BigDecimal(recognizedTotal), new BigDecimal(settled));
	}
}
