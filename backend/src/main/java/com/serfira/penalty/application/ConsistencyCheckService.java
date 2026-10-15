package com.serfira.penalty.application;

import com.serfira.contract.application.ReceivableReconciliationPort;
import com.serfira.ledger.application.ContractLedgerBalance;
import com.serfira.ledger.application.ContractLedgerBalancePort;
import com.serfira.payment.application.PaymentReconciliationPort;
import com.serfira.payment.application.PaymentReconciliationSnapshot;
import com.serfira.penalty.domain.consistency.ConsistencyCheckEngine;
import com.serfira.penalty.domain.consistency.ConsistencyDiscrepancy;
import com.serfira.penalty.domain.consistency.ContractReconciliationInput;
import com.serfira.penalty.domain.consistency.ReconciliationException;
import com.serfira.penalty.infrastructure.ReconciliationExceptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The daily job's fourth step (F1 / story L-3, Addendum §7.1/§7.3): a read-only reconciliation of one
 * contract's money invariants that <b>records</b> drift as {@code reconciliation_exception} rows and never
 * corrects it. It is the consistency analogue of {@link DailyServicingContractProcessor#age}: one
 * per-contract transaction, invoked by the {@link DailyServicingOrchestrator} after billing → penalty →
 * aging, isolated so a drift (or a read failure) for one contract neither rolls back recognized money nor
 * stops the others.
 *
 * <p>The three checks live in the pure {@link ConsistencyCheckEngine}; this service only gathers each check's
 * two sides through the owning modules' read ports — {@link PaymentReconciliationPort} (payment/allocation
 * totals), {@link ContractLedgerBalancePort} (ledger KAS/PIUTANG balances), and
 * {@link ReceivableReconciliationPort} (installment receivable residual) — then persists whatever the engine
 * reports as drift. It reads other modules only through ports, never their tables (02_TECH_SPEC.md §1).
 *
 * <p>The transaction is read-write only because it may insert {@code reconciliation_exception} rows; it
 * never writes to any financial table, so "no money moves" holds. A contract that is no longer ACTIVE makes
 * {@link ReceivableReconciliationPort#receivableResidual} raise {@code ContractStateException}, which the
 * orchestrator turns into a skip (not a failure), exactly like the other steps.
 */
@Service
public class ConsistencyCheckService {

	private static final Logger LOGGER = LoggerFactory.getLogger(ConsistencyCheckService.class);

	private final PaymentReconciliationPort payments;
	private final ContractLedgerBalancePort ledger;
	private final ReceivableReconciliationPort receivables;
	private final ReconciliationExceptionRepository exceptions;

	public ConsistencyCheckService(PaymentReconciliationPort payments, ContractLedgerBalancePort ledger,
			ReceivableReconciliationPort receivables, ReconciliationExceptionRepository exceptions) {
		this.payments = payments;
		this.ledger = ledger;
		this.receivables = receivables;
		this.exceptions = exceptions;
	}

	/**
	 * Reconciles one contract for the given business date and records any drift. Read-write (it may insert
	 * drift rows) but touches no financial table.
	 *
	 * @param contractId   contract to reconcile
	 * @param businessDate business date the check runs for ({@code reconciliation_exception.check_date})
	 * @return {@code CLEAN} when every check balanced, {@code DRIFT} when at least one failed (and was
	 *         recorded). Returning rather than throwing on drift is deliberate: the drift rows must commit,
	 *         so a drift is not a transaction failure. A thrown exception means a genuine read/processing
	 *         failure, which the orchestrator counts separately and retries.
	 */
	// REPEATABLE_READ so all five reconciliation reads see one consistent database snapshot. Under the
	// default READ COMMITTED each aggregate query sees its own freshest commit, so a POST /payments that
	// commits mid-check (billing is live while the job runs) could make the ledger KAS/PIUTANG reads
	// disagree with the earlier payment/allocation reads and record false OPEN drift. The step only reads
	// money tables, so a serialization conflict is unlikely; if one occurs the orchestrator's retry path
	// (T3/T26) handles it. (Addendum §7.1, PR #12 review.)
	@Transactional(isolation = Isolation.REPEATABLE_READ)
	public CheckOutcome check(UUID contractId, LocalDate businessDate) {
		Objects.requireNonNull(contractId, "contractId");
		Objects.requireNonNull(businessDate, "businessDate");

		PaymentReconciliationSnapshot paymentSnapshot = payments.loadReconciliationSnapshot(contractId);
		ContractLedgerBalance ledgerBalance = ledger.loadContractBalance(contractId);
		// Rejects a non-ACTIVE / schedule-less contract (409), the orchestrator's skip signal; call it last
		// so an already-skipped contract does the two cheap reads but still skips before any insert.
		var receivableResidual = receivables.receivableResidual(contractId);

		ContractReconciliationInput input = new ContractReconciliationInput(
				paymentSnapshot.postedPaymentTotal(),
				paymentSnapshot.postedAllocationTotal(),
				ledgerBalance.paymentCashReceived(),
				receivableResidual,
				ledgerBalance.receivableBalance());

		List<ConsistencyDiscrepancy> discrepancies = ConsistencyCheckEngine.check(input);
		for (ConsistencyDiscrepancy discrepancy : discrepancies) {
			// Safe metadata only: contract id, check type and the drift amount — never PII (Addendum §7.3,
			// 60-security PII logging rule). ERROR because an invariant drift is a correctness alarm.
			LOGGER.error("Consistency check {} drift for contract {}: expected {}, actual {}, diff {}",
					discrepancy.type(), contractId, discrepancy.expected(), discrepancy.actual(),
					discrepancy.diff());
			// Idempotent per (contract, business date, check type): a same-date re-run of a still-drifting
			// contract (a supported workflow) must not add a second OPEN row for one drift. Skip when an OPEN
			// row for the key already exists; the V19 uq_recon_exception_open index is the hard backstop.
			if (exceptions.existsByContractIdAndCheckDateAndCheckTypeAndStatus(
					contractId, businessDate, discrepancy.type().dbValue(), ReconciliationException.OPEN_STATUS)) {
				continue;
			}
			exceptions.save(new ReconciliationException(contractId, businessDate, discrepancy));
		}
		return discrepancies.isEmpty() ? CheckOutcome.CLEAN : CheckOutcome.DRIFT;
	}

	/** Whether a contract's reconciliation balanced ({@code CLEAN}) or drifted and was recorded ({@code DRIFT}). */
	public enum CheckOutcome {
		CLEAN,
		DRIFT
	}
}
