package com.serfira.reporting.application;

import com.serfira.contract.application.AgingReportSourcePort;
import com.serfira.contract.application.InstallmentAgingSnapshot;
import com.serfira.reporting.api.AgingBucketsResponse;
import com.serfira.reporting.api.AgingReportResponse;
import com.serfira.reporting.api.ContractAgingRow;
import com.serfira.reporting.domain.AgingBreakdown;
import com.serfira.shared.api.PageResponse;
import com.serfira.shared.clock.Clock;
import com.serfira.shared.error.BadRequestException;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the aging report (T8, PRD D-2): the portfolio buckets across all ACTIVE contracts plus a page of
 * per-contract rows. Read-only — the report writes nothing.
 *
 * <p>The DPD calendar and the outstanding formula are the {@code contract} module's rules, consumed through
 * {@link AgingReportSourcePort}; this service only buckets and aggregates (ADR-013 A-7). It is not
 * {@code @Transactional}: the single read happens inside the port implementation's read-only transaction, and
 * the rest is pure aggregation.
 */
@Service
public class AgingReportService {

	private static final int MAX_PAGE_SIZE = 100;

	private final AgingReportSourcePort source;
	private final Clock clock;

	public AgingReportService(AgingReportSourcePort source, Clock clock) {
		this.source = source;
		this.clock = clock;
	}

	/**
	 * The aging report as of {@code asOf} (today only), with the per-contract rows paged.
	 *
	 * @param asOf requested business date; {@code null} means today. Any other value is rejected.
	 * @param page 0-based page index for the per-contract rows
	 * @param size page size, 1..100
	 * @throws InvalidAsOfDateException if {@code asOf} is not today (400 {@code INVALID_AS_OF_DATE})
	 * @throws BadRequestException      for out-of-range paging (400 {@code VALIDATION_ERROR})
	 */
	public AgingReportResponse aging(LocalDate asOf, int page, int size) {
		LocalDate today = clock.today();
		LocalDate effectiveAsOf = asOf == null ? today : asOf;
		if (!effectiveAsOf.equals(today)) {
			throw new InvalidAsOfDateException("as_of must be today (" + today
					+ "); historical reconstruction is not supported in Phase 1");
		}
		if (page < 0) {
			throw new BadRequestException("page must be >= 0");
		}
		if (size < 1 || size > MAX_PAGE_SIZE) {
			throw new BadRequestException("size must be between 1 and " + MAX_PAGE_SIZE);
		}

		List<ContractAccumulator> perContract = groupByContract(source.findOutstandingInstallments(effectiveAsOf));
		AgingBreakdown portfolio = new AgingBreakdown();
		for (ContractAccumulator contract : perContract) {
			portfolio.addAll(contract.breakdown());
		}

		PageResponse<ContractAgingRow> contractsPage = page(perContract, page, size);
		return new AgingReportResponse(effectiveAsOf, AgingBucketsResponse.from(portfolio), contractsPage);
	}

	/**
	 * Groups snapshots by contract, preserving the port's deterministic order (first appearance = first
	 * contract-number order). Each contract accumulates its installments into one breakdown.
	 */
	private static List<ContractAccumulator> groupByContract(List<InstallmentAgingSnapshot> snapshots) {
		Map<UUID, ContractAccumulator> byContract = new LinkedHashMap<>();
		for (InstallmentAgingSnapshot snapshot : snapshots) {
			ContractAccumulator accumulator = byContract.computeIfAbsent(snapshot.contractId(),
					id -> new ContractAccumulator(id, snapshot.contractNo()));
			accumulator.breakdown().add(snapshot.daysPastDue(), snapshot.outstanding());
		}
		return List.copyOf(byContract.values());
	}

	/** Pages the already-ordered per-contract rows in memory (the report is a bounded read; see T8). */
	private static PageResponse<ContractAgingRow> page(List<ContractAccumulator> perContract, int page, int size) {
		int total = perContract.size();
		int totalPages = total == 0 ? 0 : (total + size - 1) / size;
		int from = Math.min(page * size, total);
		int to = Math.min(from + size, total);
		List<ContractAgingRow> rows = new ArrayList<>(to - from);
		for (ContractAccumulator contract : perContract.subList(from, to)) {
			rows.add(new ContractAgingRow(contract.contractId(), contract.contractNo(),
					AgingBucketsResponse.from(contract.breakdown())));
		}
		return PageResponse.of(rows, page, size, total, totalPages);
	}

	/** Mutable per-contract accumulator used while folding snapshots; never leaves this service. */
	private static final class ContractAccumulator {

		private final UUID contractId;
		private final String contractNo;
		private final AgingBreakdown breakdown = new AgingBreakdown();

		private ContractAccumulator(UUID contractId, String contractNo) {
			this.contractId = contractId;
			this.contractNo = contractNo;
		}

		private UUID contractId() {
			return contractId;
		}

		private String contractNo() {
			return contractNo;
		}

		private AgingBreakdown breakdown() {
			return breakdown;
		}
	}
}
