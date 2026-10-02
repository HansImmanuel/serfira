package com.serfira.contract.application;

import com.serfira.contract.domain.Installment;
import com.serfira.contract.domain.InstallmentAging;
import com.serfira.contract.domain.InstallmentBalance;
import com.serfira.contract.infrastructure.InstallmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * {@code contract}-module implementation of {@link AgingReportSourcePort}: reads every ACTIVE contract's
 * schedule and emits a snapshot per installment that still owes money, with its days-past-due and
 * outstanding computed by the module's own domain rules ({@link InstallmentAging},
 * {@link InstallmentBalance}).
 *
 * <p>Read-only: the report never writes. One query fetches all ACTIVE installments with their contract, so
 * DPD (needs {@code grace_period_days}) and the per-contract row (needs {@code contract_no}) are available
 * without a lazy walk. Keyset batching is deliberately out of scope here — it is a daily-job concern (T26);
 * the report is a bounded read served from one snapshot.
 */
@Service
@Transactional(readOnly = true)
public class AgingReportSourceService implements AgingReportSourcePort {

	private static final Logger LOGGER = LoggerFactory.getLogger(AgingReportSourceService.class);

	private final InstallmentRepository installments;

	public AgingReportSourceService(InstallmentRepository installments) {
		this.installments = installments;
	}

	@Override
	public List<InstallmentAgingSnapshot> findOutstandingInstallments(LocalDate asOf) {
		Objects.requireNonNull(asOf, "asOf");
		List<Installment> active = installments.findActiveContractInstallments();
		List<InstallmentAgingSnapshot> snapshots = new ArrayList<>(active.size());
		for (Installment installment : active) {
			BigDecimal outstanding = InstallmentBalance.of(installment).outstanding();
			if (outstanding.signum() == 0) {
				continue;
			}
			if (outstanding.signum() < 0) {
				// A negative outstanding means an over-resolved installment, i.e. corrupted data that the
				// DB-level invariant (V3 trg_installment_amounts_deferred) should have blocked. InstallmentBalance
				// keeps it visible on purpose, so the aging report must not silently drop it: it cannot sit in a
				// bucket (a bucket amount is > 0), but we log it with safe identifiers (never PII) so it is
				// traceable, matching the consistency job's "report, do not hide" stance (story L-3).
				LOGGER.warn("Skipping installment {} of contract {} from aging: outstanding is negative ({}), "
						+ "which indicates corrupted data", installment.getId(), installment.getContract().getId(),
						outstanding);
				continue;
			}
			int daysPastDue = InstallmentAging.daysPastDue(installment.getDueDate(),
					installment.getContract().getGracePeriodDays(), asOf);
			snapshots.add(new InstallmentAgingSnapshot(installment.getContract().getId(),
					installment.getContract().getContractNo(), daysPastDue, outstanding));
		}
		return List.copyOf(snapshots);
	}
}
