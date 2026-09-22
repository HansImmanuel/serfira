package com.serfira.contract.application;

import com.serfira.contract.domain.Contract;
import com.serfira.contract.domain.ContractStateException;
import com.serfira.contract.domain.Installment;
import com.serfira.contract.domain.InstallmentStatus;
import com.serfira.contract.infrastructure.ContractRepository;
import com.serfira.contract.infrastructure.InstallmentRepository;
import com.serfira.ledger.application.LedgerPostingService;
import com.serfira.ledger.domain.LedgerAccount;
import com.serfira.ledger.domain.LedgerPosting;
import com.serfira.ledger.domain.LedgerPostingLine;
import com.serfira.ledger.domain.LedgerRefType;
import com.serfira.shared.clock.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Implementation of {@link InstallmentBillingPort}: the {@code contract} module's billing/recognition
 * step (story C4, ADR-011). It owns {@code recognized_interest_amount} (DM §1.4) and posts the
 * recognition journal through the {@code ledger} module's application service (TS §3, edge
 * {@code contract → ledger} of ADR-008).
 *
 * <p><b>One transaction per call.</b> {@code REQUIRED} propagation is deliberate: inside a payment the
 * step joins the payment's transaction, so recognition and the payment commit or roll back together;
 * called by the daily job (D2) it opens the transaction itself. Posting on the ledger side is
 * {@code MANDATORY}, so this entry point is what guarantees a transaction exists.
 */
@Service
public class InstallmentBillingService implements InstallmentBillingPort {

	private static final Logger LOGGER = LoggerFactory.getLogger(InstallmentBillingService.class);

	/** Nothing to recognize: the due date has not been reached, or the interest is already billed. */
	private static final BigDecimal NOTHING_TO_BILL = BigDecimal.ZERO;

	private final ContractRepository contracts;
	private final InstallmentRepository installments;
	private final LedgerPostingService ledger;
	private final Clock clock;

	public InstallmentBillingService(ContractRepository contracts, InstallmentRepository installments,
			LedgerPostingService ledger, Clock clock) {
		this.contracts = contracts;
		this.installments = installments;
		this.ledger = ledger;
		this.clock = clock;
	}

	@Override
	@Transactional
	public void billDueInterest(UUID contractId, LocalDate businessDate) {
		Objects.requireNonNull(contractId, "contractId");
		Objects.requireNonNull(businessDate, "businessDate");
		Contract contract = contracts.findById(contractId).orElseThrow(() -> new ContractNotFoundException(contractId));
		if (!contract.isActive()) {
			throw new ContractStateException("contract " + contract.getContractNo() + " is " + contract.getStatus()
					+ " and accrues no billable interest; only an ACTIVE contract does");
		}

		BigDecimal recognizedTotal = NOTHING_TO_BILL;
		int billedInstallments = 0;
		for (Installment installment : installments.findByContractIdOrderByPeriodNo(contractId)) {
			BigDecimal delta = unbilledInterest(installment, businessDate);
			if (delta.signum() <= 0) {
				continue;
			}
			installment.recognizeInterest(delta);
			ledger.post(billingPosting(contract, installment, delta));
			recognizedTotal = recognizedTotal.add(delta);
			billedInstallments++;
		}

		// Flush inside the caller's transaction: a journal the ledger refuses (an event already posted) or
		// an amount the database refuses must fail here, in the caller's use case, not at COMMIT.
		installments.flush();
		if (billedInstallments > 0) {
			LOGGER.info("Billed {} installment(s) of contract {} for {} (recognized interest = {})",
					billedInstallments, contract.getContractNo(), businessDate, recognizedTotal);
		}
	}

	/**
	 * Interest this installment still owes recognition for, or zero when there is nothing to bill: the
	 * due date has not been reached, the installment was resolved by something other than a payment
	 * ({@code SETTLED}/{@code WRITTEN_OFF} — settlement and write-off recognize their own interest), or
	 * the scheduled interest is already fully recognized.
	 *
	 * <p>Billing the <b>delta</b> rather than only a never-recognized installment makes the step
	 * idempotent by state regardless of which flow wrote recognition before it, and it keeps
	 * zero-interest periods out of the ledger, whose lines may never carry a zero amount.
	 */
	private static BigDecimal unbilledInterest(Installment installment, LocalDate businessDate) {
		if (installment.getDueDate().isAfter(businessDate)) {
			return NOTHING_TO_BILL;
		}
		if (installment.getStatus() == InstallmentStatus.SETTLED
				|| installment.getStatus() == InstallmentStatus.WRITTEN_OFF) {
			return NOTHING_TO_BILL;
		}
		BigDecimal remaining = installment.getInterestAmount().subtract(installment.getRecognizedInterestAmount());
		return remaining.signum() > 0 ? remaining : NOTHING_TO_BILL;
	}

	/**
	 * The recognition journal of one installment (TS §3 &quot;Billing bunga installment&quot;): the
	 * scheduled interest becomes a receivable and the same amount becomes revenue. {@code entry_date} is
	 * the installment's {@code due_date} — the business date the interest became receivable, not the
	 * moment the step ran (DM §1.13, ADR-008) — and {@code ref_id} is the installment, so recognition is
	 * idempotent per installment and reconciliation can group it against that installment (Addendum §7.1).
	 */
	private LedgerPosting billingPosting(Contract contract, Installment installment, BigDecimal amount) {
		UUID contractId = contract.getId();
		return LedgerPosting.of(LedgerRefType.BILLING, installment.getId(),
				billingEntryDate(installment.getDueDate()),
				"Interest billing period " + installment.getPeriodNo() + " of " + contract.getContractNo(),
				List.of(LedgerPostingLine.debit(LedgerAccount.PIUTANG_BUNGA, amount, contractId),
						LedgerPostingLine.credit(LedgerAccount.PENDAPATAN_BUNGA, amount, contractId)));
	}

	/**
	 * Interest is receivable from the start of its due date in business time — the same convention
	 * activation uses for {@code start_date} (ADR-008), so a {@code DATE} column becomes the accounting
	 * date instead of the wall-clock time the step happened to run.
	 */
	private OffsetDateTime billingEntryDate(LocalDate dueDate) {
		return dueDate.atStartOfDay(clock.zone()).toOffsetDateTime();
	}
}
