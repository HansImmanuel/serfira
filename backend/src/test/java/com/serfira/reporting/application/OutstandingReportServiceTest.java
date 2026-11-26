package com.serfira.reporting.application;

import com.serfira.contract.application.CohortReceivableSnapshot;
import com.serfira.contract.application.InstallmentAgingSnapshot;
import com.serfira.shared.clock.FixedClock;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T36 — the service composes the owning modules' ports: it asks each for today's period and passes the cohort's
 * ids to the payment port, and it rejects any {@code as_of} other than today (ADR-013 A-6).
 */
class OutstandingReportServiceTest {

	private static final LocalDate TODAY = LocalDate.of(2026, 3, 31);
	private static final LocalDate FIRST_OF_MONTH = LocalDate.of(2026, 3, 1);

	private final UUID cohortInstallment = UUID.randomUUID();
	private final List<Object> calls = new ArrayList<>();

	private final OutstandingReportService service = new OutstandingReportService(
			asOf -> {
				calls.add("owed " + asOf);
				return List.of(new InstallmentAgingSnapshot(UUID.randomUUID(), "MF-202601-0001", "Budi Santoso", TODAY, 0,
						new BigDecimal("1573333.33")));
			},
			(from, to) -> {
				calls.add("cohort " + from + ".." + to);
				return List.of(new CohortReceivableSnapshot(cohortInstallment, new BigDecimal("1573333.33"),
						new BigDecimal("0.00")));
			},
			installmentIds -> {
				calls.add("collected " + installmentIds);
				return new BigDecimal("786666.67");
			},
			(from, to) -> {
				calls.add("cash " + from + ".." + to);
				return new BigDecimal("1000.00");
			},
			new FixedClock(TODAY));

	@Test
	void composesThePortsForTheMonthToDate() {
		OutstandingMetrics metrics = service.outstanding(null);

		assertThat(calls).containsExactlyInAnyOrder(
				"owed " + TODAY,
				"cohort " + FIRST_OF_MONTH + ".." + TODAY,
				"collected " + List.of(cohortInstallment),
				"cash " + FIRST_OF_MONTH + ".." + TODAY);
		assertThat(metrics.asOf()).isEqualTo(TODAY);
		assertThat(metrics.totalOutstanding()).isEqualByComparingTo("1573333.33");
		assertThat(metrics.accountsReceivable()).isEqualByComparingTo("1573333.33");
		assertThat(metrics.collectionRate()).isEqualTo(new BigDecimal("0.5000"));
		assertThat(metrics.settlementCash()).isEqualByComparingTo("1000.00");
	}

	@Test
	void todayIsAcceptedAndAnyOtherAsOfIsRejected() {
		assertThat(service.outstanding(TODAY).asOf()).isEqualTo(TODAY);
		assertThatThrownBy(() -> service.outstanding(TODAY.minusDays(1)))
				.isInstanceOf(InvalidAsOfDateException.class);
		assertThatThrownBy(() -> service.outstanding(TODAY.plusDays(1)))
				.isInstanceOf(InvalidAsOfDateException.class);
	}
}
