package com.serfira.penalty.application;

import com.serfira.contract.application.InstallmentPenalty;
import com.serfira.contract.application.InstallmentPenaltyPort;
import com.serfira.contract.application.InstallmentPenaltySnapshot;
import com.serfira.contract.domain.InstallmentStatus;
import com.serfira.ledger.application.LedgerPostingService;
import com.serfira.ledger.domain.LedgerAccount;
import com.serfira.ledger.domain.LedgerPosting;
import com.serfira.ledger.domain.LedgerPostingLine;
import com.serfira.ledger.domain.LedgerRefType;
import com.serfira.penalty.domain.PenaltyAccrual;
import com.serfira.penalty.domain.PenaltyCalculator;
import com.serfira.penalty.domain.PenaltyCharge;
import com.serfira.penalty.domain.PenaltyTerms;
import com.serfira.penalty.infrastructure.PenaltyAccrualRepository;
import com.serfira.shared.clock.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Implementation of {@link PenaltyAccrualPort}: the {@code penalty} module's daily recognition step
 * (story D1, ADR-012). It owns {@code penalty_accrual} (DM §1.9) and, per charged day, posts the
 * {@code PENALTY_ACCRUAL} journal through the {@code ledger} module's application service (TS §3, edge
 * {@code penalty → ledger}) and asks the {@code contract} module to raise the installment's gross
 * {@code penalty_amount} ({@link InstallmentPenaltyPort}, edge {@code penalty → contract}).
 *
 * <p>The calculation itself is a pure function ({@link PenaltyCalculator}); this class only supplies its
 * inputs — the contract's snapshotted grace/rate, each installment's due date and unpaid pokok+bunga, and the
 * accrual dates that already exist — and turns the result into rows, journals and a total.
 *
 * <p><b>Which days are charged, and why nothing suppresses a later day:</b> eligibility is decided per date
 * against the set of dates that already have a row, never against a cumulative expected total. A partial
 * payment therefore lowers the base of the following days (cheaper charges) instead of stopping them, and a
 * day that could not be charged while nothing was outstanding stays chargeable if the base is restored
 * (ADR-012 decisions 3–4).
 *
 * <p>{@code PAID} is not a skip condition: {@code PAID} marks cash received, so what decides is the base
 * (DM §1.4, ADR-011 decision 5). An installment resolved at principal only before its interest was billed
 * still has a receivable and still accrues, while a settled or written-off installment may never accrue more.
 */
@Service
public class PenaltyAccrualService implements PenaltyAccrualPort {

	private static final Logger LOGGER = LoggerFactory.getLogger(PenaltyAccrualService.class);

	private final InstallmentPenaltyPort contracts;
	private final PenaltyAccrualRepository accruals;
	private final LedgerPostingService ledger;
	private final Clock clock;

	public PenaltyAccrualService(InstallmentPenaltyPort contracts, PenaltyAccrualRepository accruals,
			LedgerPostingService ledger, Clock clock) {
		this.contracts = contracts;
		this.accruals = accruals;
		this.ledger = ledger;
		this.clock = clock;
	}

	@Override
	@Transactional
	public int accrueDuePenalty(UUID contractId, LocalDate businessDate) {
		Objects.requireNonNull(contractId, "contractId");
		Objects.requireNonNull(businessDate, "businessDate");
		InstallmentPenaltySnapshot contract = contracts.loadPenaltySnapshot(contractId);
		Map<UUID, Set<LocalDate>> alreadyAccrued = accruedDatesByInstallment(contract.installments());

		Map<UUID, BigDecimal> accruedByInstallment = new LinkedHashMap<>();
		BigDecimal recognizedTotal = BigDecimal.ZERO;
		int accruedDays = 0;
		for (InstallmentPenalty installment : contract.installments()) {
			if (installment.status() == InstallmentStatus.SETTLED
					|| installment.status() == InstallmentStatus.WRITTEN_OFF) {
				continue;
			}
			List<PenaltyCharge> charges = PenaltyCalculator.charges(new PenaltyTerms(installment.dueDate(),
					contract.gracePeriodDays(), contract.penaltyRateDaily(), installment.penaltyBase(),
					businessDate, alreadyAccrued.getOrDefault(installment.installmentId(), Set.of())));
			BigDecimal accrued = BigDecimal.ZERO;
			for (PenaltyCharge charge : charges) {
				PenaltyAccrual accrual = accruals.save(new PenaltyAccrual(installment.installmentId(),
						charge.accrualDate(), charge.daysLate(), charge.amount()));
				// One journal entry per charged day, keyed by the accrual row: the day itself is the event,
				// so a later run may still post its own days (ADR-012 decision 6).
				ledger.post(accrualPosting(contract, installment, accrual, charge));
				accrued = accrued.add(charge.amount());
				accruedDays++;
			}
			if (accrued.signum() > 0) {
				accruedByInstallment.put(installment.installmentId(), accrued);
				recognizedTotal = recognizedTotal.add(accrued);
			}
		}

		if (!accruedByInstallment.isEmpty()) {
			// Gross penalty_amount on the installment; the rows written above are its history.
			contracts.applyPenaltyAccrual(contractId, accruedByInstallment);
		}
		// Flush inside the caller's transaction: a row the database refuses must fail here, in the caller's
		// use case, not at COMMIT.
		accruals.flush();
		if (accruedDays > 0) {
			LOGGER.info("Accrued {} penalty day(s) of contract {} for {} (recognized penalty = {})",
					accruedDays, contract.contractNo(), businessDate, recognizedTotal);
		}
		return accruedDays;
	}

	/** Accrual dates already written per installment of this contract: the step's only idempotence input. */
	private Map<UUID, Set<LocalDate>> accruedDatesByInstallment(List<InstallmentPenalty> installments) {
		List<UUID> installmentIds = installments.stream().map(InstallmentPenalty::installmentId).toList();
		Map<UUID, Set<LocalDate>> dates = new HashMap<>();
		for (PenaltyAccrual accrual : accruals.findByInstallmentIdIn(installmentIds)) {
			dates.computeIfAbsent(accrual.getInstallmentId(), id -> new HashSet<>()).add(accrual.getAccrualDate());
		}
		return dates;
	}

	/**
	 * The recognition journal of one charged day (TS §3 &quot;Penalty accrual harian&quot;): the day's penalty
	 * becomes a receivable and the same amount becomes revenue. {@code entry_date} is the accrual date — the
	 * business date that was late, not the moment the step ran (DM §1.13, ADR-008) — and {@code ref_id} is the
	 * accrual row, so reconciliation can group the entry against the day that produced it (Addendum §7.1).
	 */
	private LedgerPosting accrualPosting(InstallmentPenaltySnapshot contract, InstallmentPenalty installment,
			PenaltyAccrual accrual, PenaltyCharge charge) {
		UUID contractId = contract.contractId();
		return LedgerPosting.of(LedgerRefType.PENALTY_ACCRUAL, accrual.getId(), accrualEntryDate(charge.accrualDate()),
				"Penalty accrual day " + charge.daysLate() + " of period " + installment.periodNo()
						+ " of " + contract.contractNo(),
				List.of(LedgerPostingLine.debit(LedgerAccount.PIUTANG_DENDA, charge.amount(), contractId),
						LedgerPostingLine.credit(LedgerAccount.PENDAPATAN_DENDA, charge.amount(), contractId)));
	}

	/**
	 * A day's penalty is receivable from the start of that day in business time — the same convention billing
	 * uses for {@code due_date} (ADR-008, ADR-011), so a {@code DATE} column becomes the accounting date
	 * instead of the wall-clock time the step happened to run.
	 */
	private OffsetDateTime accrualEntryDate(LocalDate accrualDate) {
		return accrualDate.atStartOfDay(clock.zone()).toOffsetDateTime();
	}
}
