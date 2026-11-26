package com.serfira.reporting.application;

import com.serfira.contract.application.AgingReportSourcePort;
import com.serfira.contract.application.CohortReceivableSnapshot;
import com.serfira.contract.application.CollectionCohortSourcePort;
import com.serfira.payment.application.CollectedAllocationPort;
import com.serfira.settlement.application.SettlementCashPort;
import com.serfira.shared.clock.Clock;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Builds the outstanding report (T36, PRD R-1, ADR-026): composes the read-only ports of the modules that own
 * the data and hands their values to {@link OutstandingMetricsCalculator}. Read-only — the report writes nothing.
 *
 * <p>Not {@code @Transactional}: each port reads inside its own read-only transaction, as
 * {@link AgingReportService} does, so the ports share no database snapshot with each other. (The aging source
 * port reads its own statements at REPEATABLE_READ, so they agree among themselves; the other ports read at
 * READ COMMITTED, where not even one port's statements share a snapshot.) Any number of payments, voids, credit
 * applications, settlements, penalty accruals or adjustments can commit between them, so under concurrent
 * financial writes one response can mix values read at different points in time, and its metrics need not
 * reconcile with each other. That is accepted because the report is not a ledger (T36 implementation notes).
 */
@Service
public class OutstandingReportService {

	private final AgingReportSourcePort owedInstallments;
	private final CollectionCohortSourcePort cohort;
	private final CollectedAllocationPort collected;
	private final SettlementCashPort settlementCash;
	private final Clock clock;

	public OutstandingReportService(AgingReportSourcePort owedInstallments, CollectionCohortSourcePort cohort,
			CollectedAllocationPort collected, SettlementCashPort settlementCash, Clock clock) {
		this.owedInstallments = owedInstallments;
		this.cohort = cohort;
		this.collected = collected;
		this.settlementCash = settlementCash;
		this.clock = clock;
	}

	/**
	 * The metrics as of {@code asOf} (today only).
	 *
	 * @param asOf requested business date; {@code null} means today. Any other value is rejected.
	 * @throws InvalidAsOfDateException if {@code asOf} is not today (400 {@code INVALID_AS_OF_DATE})
	 */
	public OutstandingMetrics outstanding(LocalDate asOf) {
		LocalDate today = clock.today();
		LocalDate effectiveAsOf = asOf == null ? today : asOf;
		if (!effectiveAsOf.equals(today)) {
			throw new InvalidAsOfDateException("as_of must be today (" + today
					+ "); historical reconstruction is not supported");
		}
		LocalDate periodFrom = OutstandingMetricsCalculator.periodFrom(effectiveAsOf);
		List<CohortReceivableSnapshot> cohortInstallments = cohort.findCohort(periodFrom, effectiveAsOf);
		List<UUID> cohortIds = cohortInstallments.stream().map(CohortReceivableSnapshot::installmentId).toList();
		BigDecimal collectedOnCohort = collected.sumCollected(cohortIds);
		BigDecimal cash = settlementCash.sumCashReceived(periodFrom, effectiveAsOf);
		return OutstandingMetricsCalculator.calculate(effectiveAsOf,
				owedInstallments.findOutstandingInstallments(effectiveAsOf), cohortInstallments, collectedOnCohort,
				cash);
	}
}
