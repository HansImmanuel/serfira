package com.serfira.reporting.application;

import com.serfira.contract.application.AgingReportSourcePort;
import com.serfira.contract.application.InstallmentAgingSnapshot;
import com.serfira.reporting.api.AgingReportResponse;
import com.serfira.reporting.api.ContractAgingRow;
import com.serfira.shared.clock.FixedClock;
import com.serfira.shared.error.BadRequestException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T8 — the report service's own logic (as_of validation, grouping, portfolio aggregation, paging) with a
 * stub source port and a {@link FixedClock}, no Spring and no database.
 */
class AgingReportServiceTest {

	private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);

	private final FixedClock clock = new FixedClock(TODAY);

	@Test
	void nullAsOfDefaultsToTodayAndBucketsPerContractAndPortfolio() {
		UUID contractA = UUID.randomUUID();
		UUID contractB = UUID.randomUUID();
		AgingReportService service = service(
				new InstallmentAgingSnapshot(contractA, "MF-202601-0001", 0, new BigDecimal("100.00")),
				new InstallmentAgingSnapshot(contractA, "MF-202601-0001", 45, new BigDecimal("300.00")),
				new InstallmentAgingSnapshot(contractB, "MF-202601-0002", 91, new BigDecimal("400.00")));

		AgingReportResponse report = service.aging(null, 0, 20);

		assertThat(report.asOf()).isEqualTo(TODAY);
		assertThat(report.portfolio().current()).isEqualByComparingTo("100.00");
		assertThat(report.portfolio().dpd31To60()).isEqualByComparingTo("300.00");
		assertThat(report.portfolio().dpdOver90()).isEqualByComparingTo("400.00");
		assertThat(report.portfolio().totalOutstanding()).isEqualByComparingTo("800.00");

		assertThat(report.contracts().totalElements()).isEqualTo(2L);
		List<ContractAgingRow> rows = report.contracts().content();
		assertThat(rows).hasSize(2);
		// Deterministic order preserved from the port (contract-number order).
		ContractAgingRow rowA = rows.get(0);
		assertThat(rowA.contractNo()).isEqualTo("MF-202601-0001");
		assertThat(rowA.contractId()).isEqualTo(contractA);
		assertThat(rowA.buckets().current()).isEqualByComparingTo("100.00");
		assertThat(rowA.buckets().dpd31To60()).isEqualByComparingTo("300.00");
		assertThat(rowA.buckets().totalOutstanding()).isEqualByComparingTo("400.00");
		assertThat(rows.get(1).buckets().dpdOver90()).isEqualByComparingTo("400.00");
	}

	@Test
	void explicitTodayIsAcceptedAndAPastOrFutureAsOfIsRejected() {
		AgingReportService service = service();

		assertThat(service.aging(TODAY, 0, 20).asOf()).isEqualTo(TODAY);
		assertThatThrownBy(() -> service.aging(TODAY.minusDays(1), 0, 20))
				.isInstanceOf(InvalidAsOfDateException.class);
		assertThatThrownBy(() -> service.aging(TODAY.plusDays(1), 0, 20))
				.isInstanceOf(InvalidAsOfDateException.class);
	}

	@Test
	void pagesThePerContractRowsWhilePortfolioSpansEveryContract() {
		AgingReportService service = service(
				new InstallmentAgingSnapshot(UUID.randomUUID(), "MF-202601-0001", 10, new BigDecimal("100.00")),
				new InstallmentAgingSnapshot(UUID.randomUUID(), "MF-202601-0002", 10, new BigDecimal("200.00")),
				new InstallmentAgingSnapshot(UUID.randomUUID(), "MF-202601-0003", 10, new BigDecimal("300.00")));

		AgingReportResponse firstPage = service.aging(null, 0, 2);
		assertThat(firstPage.contracts().content()).hasSize(2);
		assertThat(firstPage.contracts().totalElements()).isEqualTo(3L);
		assertThat(firstPage.contracts().totalPages()).isEqualTo(2);
		// The portfolio total is the whole book regardless of the page.
		assertThat(firstPage.portfolio().totalOutstanding()).isEqualByComparingTo("600.00");

		AgingReportResponse secondPage = service.aging(null, 1, 2);
		assertThat(secondPage.contracts().content()).hasSize(1);
		assertThat(secondPage.contracts().content().get(0).contractNo()).isEqualTo("MF-202601-0003");
		assertThat(secondPage.portfolio().totalOutstanding()).isEqualByComparingTo("600.00");
	}

	@Test
	void anEmptyBookIsAllZerosAndAnEmptyPage() {
		AgingReportResponse report = service().aging(null, 0, 20);

		assertThat(report.portfolio().totalOutstanding()).isEqualByComparingTo("0.00");
		assertThat(report.contracts().content()).isEmpty();
		assertThat(report.contracts().totalElements()).isZero();
		assertThat(report.contracts().totalPages()).isZero();
	}

	@Test
	void rejectsOutOfRangePaging() {
		AgingReportService service = service();

		assertThatThrownBy(() -> service.aging(null, -1, 20)).isInstanceOf(BadRequestException.class);
		assertThatThrownBy(() -> service.aging(null, 0, 0)).isInstanceOf(BadRequestException.class);
		assertThatThrownBy(() -> service.aging(null, 0, 101)).isInstanceOf(BadRequestException.class);
	}

	@Test
	void aPageOffsetThatOverflowsIntMultiplicationStillReturnsAnEmptyPage() {
		AgingReportService service = service(
				new InstallmentAgingSnapshot(UUID.randomUUID(), "MF-202601-0001", 10, new BigDecimal("100.00")));

		// page * size overflows a signed int (21474837 * 100 > Integer.MAX_VALUE); the offset must be computed
		// in long arithmetic and clamped, not wrap negative into subList.
		AgingReportResponse report = service.aging(null, 21_474_837, 100);

		assertThat(report.contracts().content()).isEmpty();
		assertThat(report.contracts().totalElements()).isEqualTo(1L);
		assertThat(report.portfolio().totalOutstanding()).isEqualByComparingTo("100.00");
	}

	private AgingReportService service(InstallmentAgingSnapshot... snapshots) {
		AgingReportSourcePort source = asOf -> List.of(snapshots);
		return new AgingReportService(source, clock);
	}
}
