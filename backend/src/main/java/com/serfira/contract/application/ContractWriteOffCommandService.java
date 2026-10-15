package com.serfira.contract.application;

import com.serfira.contract.api.WriteOffContractRequest;
import com.serfira.contract.api.WriteOffResponse;
import com.serfira.contract.api.WriteOffResponse.WrittenOffInstallment;
import com.serfira.contract.domain.Contract;
import com.serfira.contract.domain.ContractStateException;
import com.serfira.contract.domain.Installment;
import com.serfira.contract.domain.InstallmentStatus;
import com.serfira.contract.domain.writeoff.WriteOffEngine;
import com.serfira.contract.domain.writeoff.WriteOffInstallmentInput;
import com.serfira.contract.domain.writeoff.WriteOffLine;
import com.serfira.contract.infrastructure.ContractRepository;
import com.serfira.contract.infrastructure.InstallmentRepository;
import com.serfira.ledger.application.LedgerPostingService;
import com.serfira.ledger.domain.LedgerAccount;
import com.serfira.ledger.domain.LedgerPosting;
import com.serfira.ledger.domain.LedgerPostingLine;
import com.serfira.ledger.domain.LedgerRefType;
import com.serfira.penalty.application.EffectivePenaltyPort;
import com.serfira.penalty.application.InstallmentEffectivePenalty;
import com.serfira.shared.audit.AuditContext;
import com.serfira.shared.clock.Clock;
import com.serfira.shared.error.BadRequestException;
import com.serfira.shared.idempotency.CanonicalRequestJson;
import com.serfira.shared.idempotency.IdempotencyService;
import com.serfira.shared.idempotency.IdempotentResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The write-off use case (F5, task T19, DM §1.3/§1.12): record an external decision to stop collecting on
 * an ACTIVE contract — cap its recognized receivable, close it {@code ACTIVE → TERMINATED}, move its open
 * installments to {@code WRITTEN_OFF}, and post one balanced {@code WRITE_OFF} journal. Owner module:
 * {@code contract}.
 *
 * <p><b>One transaction per use case</b> (TS §2.3, write-path order validate → compute → write aggregate →
 * post journal → commit): the installment write-offs, the contract termination and the single journal
 * entry commit or roll back together.
 *
 * <p><b>Accrue before resolve</b> (ADR-013 §1): in this same transaction it first bills due interest and
 * accrues due penalty through the write-off business date, so the receivable it caps reflects the base
 * actually in force (the write-off analogue of the payment, quote and settlement paths). A write-off does
 * not change the base downward in a way that could under-recognize later charges — the contract is
 * TERMINATED afterwards, so nothing more accrues.
 *
 * <p><b>Idempotency</b> (ADR-017): wrapped by {@link IdempotencyService} exactly like {@code POST
 * /payments} / {@code POST /settlements}. An identical retry replays the stored response; a spent key is
 * 409 {@code IDEMPOTENCY_KEY_EXPIRED}; the same key with a different body is 409 {@code CONFLICT}.
 *
 * <p><b>Audit actor</b> (60-security): the recording actor is the authenticated JWT {@code sub} read from
 * {@link AuditContext}, never a request-body value; it is written to {@code contract.write_off_recorded_by}
 * (required by V19).
 *
 * <p><b>Journal {@code ref_id}</b>: the contract id, {@code ref_type = WRITE_OFF}. A contract is written
 * off exactly once (it must be ACTIVE), so one non-reversal entry per {@code (WRITE_OFF, contractId)} is
 * correct; {@code WRITE_OFF} is kept out of {@code uq_journal_entry_event} and the
 * {@link LedgerPostingService} service guard enforces the one-entry rule (ADR-008 d5, as PAYMENT /
 * CREDIT_APPLICATION / SETTLEMENT).
 */
@Service
public class ContractWriteOffCommandService {

	private static final Logger LOGGER = LoggerFactory.getLogger(ContractWriteOffCommandService.class);

	/** Idempotency scope of write-off (TS §2.2: keys are endpoint-scoped). */
	static final String WRITE_OFF_ENDPOINT = "POST /api/v1/contracts/{id}/write-off";

	private static final int MONEY_SCALE = 2;
	private static final BigDecimal ZERO_MONEY = BigDecimal.ZERO.setScale(MONEY_SCALE);

	private final ContractRepository contracts;
	private final InstallmentRepository installments;
	private final InstallmentBillingPort billing;
	private final com.serfira.penalty.application.PenaltyAccrualPort penalty;
	private final EffectivePenaltyPort effectivePenalty;
	private final IdempotencyService idempotency;
	private final LedgerPostingService ledger;
	private final AuditContext auditContext;
	private final Clock clock;

	public ContractWriteOffCommandService(ContractRepository contracts, InstallmentRepository installments,
			InstallmentBillingPort billing, com.serfira.penalty.application.PenaltyAccrualPort penalty,
			EffectivePenaltyPort effectivePenalty, IdempotencyService idempotency, LedgerPostingService ledger,
			AuditContext auditContext, Clock clock) {
		this.contracts = contracts;
		this.installments = installments;
		this.billing = billing;
		this.penalty = penalty;
		this.effectivePenalty = effectivePenalty;
		this.idempotency = idempotency;
		this.ledger = ledger;
		this.auditContext = auditContext;
		this.clock = clock;
	}

	/**
	 * Writes off a contract. An identical retry (same {@code Idempotency-Key} and body) replays the stored
	 * response; a spent key is 409 {@code IDEMPOTENCY_KEY_EXPIRED}.
	 *
	 * @param contractId     the contract to write off; must be ACTIVE
	 * @param idempotencyKey the client's {@code Idempotency-Key}
	 * @param request        the write-off reason
	 * @throws ContractNotFoundException if no contract has that id (404)
	 * @throws ContractStateException    if the contract is not ACTIVE (409)
	 * @throws BadRequestException       if the body/reason is missing or blank, or the key is missing (400)
	 */
	@Transactional
	public WriteOffResponse writeOff(UUID contractId, String idempotencyKey, WriteOffContractRequest request) {
		if (request == null || request.reason() == null || request.reason().isBlank()) {
			throw new BadRequestException("a write-off reason is required");
		}
		String key = idempotencyKey == null ? null : idempotencyKey.trim();
		IdempotentResult<WriteOffResponse> result = idempotency.execute(
				WRITE_OFF_ENDPOINT, key, canonicalize(contractId, request), WriteOffResponse.class,
				() -> terminate(contractId, request));
		return result.response();
	}

	/**
	 * The mutation itself, inside the idempotency claim and the caller's transaction (validate → compute →
	 * write aggregate → post journal → commit).
	 */
	private WriteOffResponse terminate(UUID contractId, WriteOffContractRequest request) {
		Contract contract = contracts.findById(contractId)
				.orElseThrow(() -> new ContractNotFoundException(contractId));
		if (!contract.isActive()) {
			throw new ContractStateException("contract " + contract.getContractNo() + " is " + contract.getStatus()
					+ " and cannot be written off; only an ACTIVE contract can");
		}

		LocalDate businessDate = clock.today();
		OffsetDateTime writtenOffAt = clock.now();

		// Accrue before resolve (ADR-013 §1): recognize due interest, then accrue due penalty, on one
		// captured business date, before capping — so the written-off receivable reflects the base in force.
		billing.billDueInterest(contractId, businessDate);
		penalty.accrueDuePenalty(contractId, businessDate);

		List<Installment> schedule = installments.findByContractIdOrderByPeriodNo(contractId);
		// One effective-penalty snapshot funds both the effective (remaining) and the paid-penalty terms:
		// loading it twice would repeat the gross-penalty, adjustment-total and paid-penalty reads.
		Map<UUID, BigDecimal> effectiveByInstallment = new HashMap<>();
		Map<UUID, BigDecimal> paidPenaltyByInstallment = new HashMap<>();
		for (InstallmentEffectivePenalty installment : effectivePenalty.loadEffectivePenalty(contractId).installments()) {
			effectiveByInstallment.put(installment.installmentId(), installment.effective());
			paidPenaltyByInstallment.put(installment.installmentId(), installment.paidPenalty());
		}

		List<WriteOffLine> lines = WriteOffEngine.cap(toEngineInputs(schedule, effectiveByInstallment,
				paidPenaltyByInstallment));

		// Cap each open installment onto written_off_amount and move it to WRITTEN_OFF (invariant 15). The
		// per-installment total never passes its outstanding receivable (V3 backstop).
		Map<UUID, Installment> byId = new HashMap<>();
		for (Installment installment : schedule) {
			byId.put(installment.getId(), installment);
		}
		List<WrittenOffInstallment> writtenOff = new ArrayList<>();
		for (WriteOffLine line : lines) {
			Installment installment = byId.get(line.installmentId());
			installment.writeOff(line.total(), writtenOffAt);
			writtenOff.add(new WrittenOffInstallment(line.installmentId(), line.periodNo(), line.total()));
		}

		// Close the contract TERMINATED with the recording actor (never the body) and the write-off reason.
		UUID recordedBy = auditContext.actorId();
		contract.terminateByWriteOff(request.reason(), recordedBy, writtenOffAt);
		contracts.saveAndFlush(contract);
		installments.flush();

		// One balanced WRITE_OFF entry: Dr BIAYA_PENGHAPUSAN_PIUTANG for the total capped receivable, Cr the
		// three receivable accounts for their per-component sums. A zero component contributes no line.
		ComponentTotals totals = sumComponents(lines);
		if (totals.total().signum() > 0) {
			ledger.post(LedgerPosting.of(LedgerRefType.WRITE_OFF, contractId, writtenOffAt,
					"Write-off of contract " + contract.getContractNo(),
					journalLines(contractId, totals)));
		}

		LOGGER.info("Wrote off contract {} (id={}, {} installment(s), total {})",
				contract.getContractNo(), contractId, writtenOff.size(), totals.total());
		return new WriteOffResponse(contractId, contract.getContractNo(), contract.getStatus().name(),
				contract.getWriteOffReason(), writtenOffAt, totals.total(), writtenOff);
	}

	/** Maps the schedule into the pure engine's input, with per-installment effective + paid penalty. */
	private static List<WriteOffInstallmentInput> toEngineInputs(List<Installment> schedule,
			Map<UUID, BigDecimal> effectiveByInstallment, Map<UUID, BigDecimal> paidPenaltyByInstallment) {
		List<WriteOffInstallmentInput> inputs = new ArrayList<>(schedule.size());
		for (Installment installment : schedule) {
			boolean terminal = installment.getStatus() == InstallmentStatus.SETTLED
					|| installment.getStatus() == InstallmentStatus.WRITTEN_OFF;
			BigDecimal effective = effectiveByInstallment.getOrDefault(installment.getId(), ZERO_MONEY);
			BigDecimal paidPenalty = paidPenaltyByInstallment.getOrDefault(installment.getId(), ZERO_MONEY);
			BigDecimal resolved = installment.getPaidAmount()
					.add(installment.getSettledAmount())
					.add(installment.getWrittenOffAmount());
			BigDecimal resolvedBeyondPenalty = max0(resolved.subtract(paidPenalty));
			inputs.add(new WriteOffInstallmentInput(installment.getId(), installment.getPeriodNo(), terminal,
					installment.getPrincipalAmount(), installment.getRecognizedInterestAmount(), effective,
					resolvedBeyondPenalty));
		}
		return inputs;
	}

	/**
	 * The write-off journal (DM §1.13): debit {@code BIAYA_PENGHAPUSAN_PIUTANG} for the total written off,
	 * credit one receivable account per non-zero component. The debit equals the sum of the credits, so the
	 * entry balances (invariant 1).
	 */
	private static List<LedgerPostingLine> journalLines(UUID contractId, ComponentTotals totals) {
		List<LedgerPostingLine> lines = new ArrayList<>();
		lines.add(LedgerPostingLine.debit(LedgerAccount.BIAYA_PENGHAPUSAN_PIUTANG, totals.total(), contractId));
		addCredit(lines, LedgerAccount.PIUTANG_DENDA, totals.penalty(), contractId);
		addCredit(lines, LedgerAccount.PIUTANG_BUNGA, totals.interest(), contractId);
		addCredit(lines, LedgerAccount.PIUTANG_POKOK, totals.principal(), contractId);
		return lines;
	}

	private static void addCredit(List<LedgerPostingLine> lines, LedgerAccount account, BigDecimal amount,
			UUID contractId) {
		if (amount.signum() > 0) {
			lines.add(LedgerPostingLine.credit(account, amount, contractId));
		}
	}

	private static ComponentTotals sumComponents(List<WriteOffLine> lines) {
		BigDecimal penalty = ZERO_MONEY;
		BigDecimal interest = ZERO_MONEY;
		BigDecimal principal = ZERO_MONEY;
		for (WriteOffLine line : lines) {
			penalty = penalty.add(line.penalty());
			interest = interest.add(line.interest());
			principal = principal.add(line.principal());
		}
		return new ComponentTotals(penalty, interest, principal);
	}

	private static BigDecimal max0(BigDecimal value) {
		return value.signum() > 0 ? value.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY) : ZERO_MONEY;
	}

	private static String canonicalize(UUID contractId, WriteOffContractRequest request) {
		Map<String, String> fields = CanonicalRequestJson.fields();
		fields.put("contract_id", contractId == null ? null : contractId.toString());
		fields.put("reason", CanonicalRequestJson.text(request.reason()));
		return CanonicalRequestJson.render(fields);
	}

	/** Per-component receivable totals across the contract's open installments. */
	private record ComponentTotals(BigDecimal penalty, BigDecimal interest, BigDecimal principal) {

		BigDecimal total() {
			return penalty.add(interest).add(principal);
		}
	}
}
