package com.serfira.payment.application;

import com.serfira.contract.application.ContractReceivableSnapshot;
import com.serfira.contract.application.InstallmentBillingPort;
import com.serfira.contract.application.InstallmentReceivable;
import com.serfira.contract.application.InstallmentReceivablePort;
import com.serfira.contract.domain.ContractStateException;
import com.serfira.contract.domain.ContractStatus;
import com.serfira.ledger.application.LedgerPostingService;
import com.serfira.ledger.domain.LedgerAccount;
import com.serfira.ledger.domain.LedgerPosting;
import com.serfira.ledger.domain.LedgerPostingLine;
import com.serfira.ledger.domain.LedgerRefType;
import com.serfira.payment.api.PaymentRequest;
import com.serfira.payment.api.PaymentResponse;
import com.serfira.payment.domain.AllocationType;
import com.serfira.payment.domain.Payment;
import com.serfira.payment.domain.PaymentAllocation;
import com.serfira.payment.domain.PaymentStatus;
import com.serfira.payment.domain.allocation.AllocationLine;
import com.serfira.payment.domain.allocation.AllocationResult;
import com.serfira.payment.domain.allocation.InstallmentAllocationInput;
import com.serfira.payment.domain.allocation.PaymentAllocationEngine;
import com.serfira.payment.infrastructure.ActiveAllocationTotal;
import com.serfira.payment.infrastructure.PaymentAllocationRepository;
import com.serfira.payment.infrastructure.PaymentRepository;
import com.serfira.penalty.application.PenaltyAccrualPort;
import com.serfira.shared.clock.Clock;
import com.serfira.shared.document.DocumentNumberGenerator;
import com.serfira.shared.document.DocumentType;
import com.serfira.shared.error.BadRequestException;
import com.serfira.shared.error.IdempotencyKeyExpiredException;
import com.serfira.shared.idempotency.CanonicalRequestJson;
import com.serfira.shared.idempotency.IdempotencyService;
import com.serfira.shared.idempotency.IdempotentResult;
import com.serfira.shared.money.DecimalBounds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The payment use case: receive money and resolve it against a contract's receivable (PRD P-1–P-4).
 *
 * <p><b>One transaction per use case</b> (TS §2.3): the payment row, its allocations, the installment
 * resolution, and the journal entry either all commit or none do — the idempotency claim is taken
 * inside the same transaction ({@code Propagation.MANDATORY}), so a failed attempt leaves no consumed
 * key and a retry can succeed (TS §2.5).
 *
 * <p><b>Module boundaries</b> (02_TECH_SPEC.md §1, ADR-010): this service owns {@code payment} and
 * {@code payment_allocation} and never touches {@code installment}. It asks the {@code contract} module
 * to bill the contract's due interest ({@link InstallmentBillingPort}), asks the {@code penalty} module
 * to recognize every due late-day charge ({@link PenaltyAccrualPort}), then reads a receivable snapshot
 * through {@link InstallmentReceivablePort} and hands the engine's resolved amounts back. It posts the
 * payment journal through the {@code ledger} module's application service.
 *
 * <p>Scope: money received is recorded and allocated. Voiding a payment (E4) and turning EXCESS into a
 * {@code contract_credit} row with an application history (E3) are later stories — here an excess is
 * recorded as an EXCESS allocation and credited to {@code TITIPAN_NASABAH}, which is the complete
 * accounting of the money event.
 *
 * <p>Interest becomes receivable at its due date (TS §5, Addendum §12), so the use case bills the
 * contract's due installments inside this transaction before allocating (story C4, ADR-011): a payment
 * received on the due date resolves interest, never only principal. The contract's maturity close is
 * the {@code contract} module's own step, triggered by the resolution below (invariant 17).
 */
@Service
public class PaymentApplicationService {

	private static final Logger LOGGER = LoggerFactory.getLogger(PaymentApplicationService.class);

	/** Idempotency scope of the payment endpoint (TS §2.2: keys are endpoint-scoped). */
	static final String CREATE_ENDPOINT = "POST /api/v1/payments";

	/**
	 * Permanent backstop that enforces one POSTED payment per idempotency key even if the
	 * {@code idempotency_keys} row is gone (V1, {@code WHERE status = 'POSTED'}). A hit means the key
	 * was already spent, so it maps to an expired-key rejection, not a retryable conflict (ADR-017).
	 */
	static final String PAYMENT_IDEMPOTENCY_CONSTRAINT = "uq_payment_idempotency";

	private static final int MONEY_SCALE = 2;
	private static final BigDecimal ZERO_MONEY = BigDecimal.ZERO.setScale(MONEY_SCALE);

	/** Credit lines in the documented order (TS §3): penalty, interest, principal, customer credit. */
	private static final List<AllocationType> CREDIT_ORDER =
			List.of(AllocationType.PENALTY, AllocationType.INTEREST, AllocationType.PRINCIPAL, AllocationType.EXCESS);

	/**
	 * The allocation engine is a stateless pure calculator (story C2) — keeping it a constant makes that
	 * purity explicit and avoids a bean that would only hold stateless behaviour.
	 */
	private static final PaymentAllocationEngine ALLOCATION_ENGINE = new PaymentAllocationEngine();

	private final PaymentRepository payments;
	private final PaymentAllocationRepository allocations;
	private final InstallmentReceivablePort receivables;
	private final InstallmentBillingPort billing;
	private final PenaltyAccrualPort penalty;
	private final DocumentNumberGenerator documentNumbers;
	private final IdempotencyService idempotency;
	private final LedgerPostingService ledger;
	private final Clock clock;

	public PaymentApplicationService(PaymentRepository payments, PaymentAllocationRepository allocations,
			InstallmentReceivablePort receivables, InstallmentBillingPort billing, PenaltyAccrualPort penalty,
			DocumentNumberGenerator documentNumbers, IdempotencyService idempotency, LedgerPostingService ledger,
			Clock clock) {
		this.payments = payments;
		this.allocations = allocations;
		this.receivables = receivables;
		this.billing = billing;
		this.penalty = penalty;
		this.documentNumbers = documentNumbers;
		this.idempotency = idempotency;
		this.ledger = ledger;
		this.clock = clock;
	}

	/**
	 * Receives one payment. Requires the client's {@code Idempotency-Key}: an identical retry replays
	 * the stored response (same payment, same number, no second journal entry) instead of receiving the
	 * money twice, and the same key with a different body is 409 (TS §2.5).
	 *
	 * @throws BadRequestException if the payload is unusable or the key is missing/malformed
	 */
	@Transactional
	public PaymentResponse create(String idempotencyKey, PaymentRequest request) {
		BigDecimal amount = requireAmount(request);
		String key = idempotencyKey == null ? null : idempotencyKey.trim();
		IdempotentResult<PaymentResponse> result = idempotency.execute(
				CREATE_ENDPOINT, key, canonicalize(request, amount), PaymentResponse.class,
				() -> receive(key, request, amount));
		return result.response();
	}

	/**
	 * The mutation itself: capture one business date, bill due interest, recognize due penalty, read the
	 * receivable, allocate, persist, resolve installments, and post the payment journal. Runs inside the
	 * idempotency claim and the caller's transaction, so a replay skips every financial step and any later
	 * failure rolls them all back together.
	 */
	private PaymentResponse receive(String idempotencyKey, PaymentRequest request, BigDecimal amount) {
		LocalDate businessDate = clock.today();
		// Billing must precede penalty because the daily charge is based on recognized principal and interest.
		// Both steps join this payment transaction and use this single captured date; the snapshot and allocation
		// therefore observe exactly the receivables recognized by this attempt (ADR-013).
		billing.billDueInterest(request.contractId(), businessDate);
		penalty.accrueDuePenalty(request.contractId(), businessDate);
		ContractReceivableSnapshot snapshot = receivables.loadReceivableSnapshot(request.contractId());
		if (snapshot.status() != ContractStatus.ACTIVE) {
			// The port already refuses anything else; the same invariant is asserted again where money is
			// written, so a future caller cannot resolve money against a closed contract by accident.
			throw new ContractStateException("contract " + snapshot.contractNo() + " is " + snapshot.status()
					+ " and cannot receive money; only an ACTIVE contract can");
		}

		AllocationResult allocation =
				ALLOCATION_ENGINE.allocate(amount, businessDate, toAllocationInputs(snapshot));

		OffsetDateTime paidAt = clock.now();
		Payment payment = new Payment(documentNumbers.next(DocumentType.PAYMENT), snapshot.contractId(), amount,
				request.channel(), paidAt, idempotencyKey);
		try {
			payments.saveAndFlush(payment);
		} catch (DataIntegrityViolationException ex) {
			throw translatePaymentViolation(ex);
		}
		allocations.saveAll(allocation.lines().stream()
				.map(line -> PaymentAllocation.of(payment, line))
				.toList());
		allocations.flush();

		receivables.applyPaymentResolution(snapshot.contractId(), resolvedByInstallment(allocation), paidAt);
		ledger.post(LedgerPosting.of(LedgerRefType.PAYMENT, payment.getId(), paidAt,
				"Payment " + payment.getPaymentNo() + " for contract " + snapshot.contractNo(),
				journalLines(payment, snapshot, allocation)));

		LOGGER.info("Received payment {} (id={}, contract={}, amount={}, excess={})", payment.getPaymentNo(),
				payment.getId(), snapshot.contractNo(), amount, allocation.excessAmount());
		return PaymentResponse.from(payment, allocation);
	}

	/**
	 * Maps the contract's receivable snapshot plus this module's <b>active</b> allocations into the
	 * engine's input (ADR-009): the contract side supplies the caps, the payment side supplies what POSTED
	 * payments have already resolved.
	 */
	private List<InstallmentAllocationInput> toAllocationInputs(ContractReceivableSnapshot snapshot) {
		List<UUID> installmentIds = snapshot.installments().stream()
				.map(InstallmentReceivable::installmentId)
				.toList();
		Map<UUID, Map<AllocationType, BigDecimal>> active = activeAllocationsByInstallment(installmentIds);

		return snapshot.installments().stream()
				.map(installment -> new InstallmentAllocationInput(
						installment.installmentId(),
						installment.periodNo(),
						installment.dueDate(),
						installment.principalAmount(),
						installment.recognizedInterestAmount(),
						installment.penaltyAmount(),
						installment.penaltyAdjustments(),
						activeAmount(active, installment.installmentId(), AllocationType.PENALTY),
						activeAmount(active, installment.installmentId(), AllocationType.INTEREST),
						activeAmount(active, installment.installmentId(), AllocationType.PRINCIPAL),
						installment.settledAmount(),
						installment.writtenOffAmount()))
				.toList();
	}

	private Map<UUID, Map<AllocationType, BigDecimal>> activeAllocationsByInstallment(List<UUID> installmentIds) {
		Map<UUID, Map<AllocationType, BigDecimal>> totals = new HashMap<>();
		for (ActiveAllocationTotal total : allocations.sumActiveByInstallmentIds(PaymentStatus.POSTED,
				installmentIds)) {
			totals.computeIfAbsent(total.installmentId(), id -> new EnumMap<>(AllocationType.class))
					.put(total.type(), total.amount());
		}
		return totals;
	}

	private static BigDecimal activeAmount(Map<UUID, Map<AllocationType, BigDecimal>> active, UUID installmentId,
			AllocationType type) {
		Map<AllocationType, BigDecimal> perInstallment = active.get(installmentId);
		return perInstallment == null ? ZERO_MONEY : perInstallment.getOrDefault(type, ZERO_MONEY);
	}

	/**
	 * Resolved amount per installment, EXCESS excluded: what the allocation did to the contract's
	 * installments, in the order the engine produced (the {@code contract} module stamps each row).
	 */
	private static Map<UUID, BigDecimal> resolvedByInstallment(AllocationResult allocation) {
		Map<UUID, BigDecimal> resolved = new LinkedHashMap<>();
		for (AllocationLine line : allocation.lines()) {
			if (line.type() != AllocationType.EXCESS) {
				resolved.merge(line.installmentRef(), line.amount(), BigDecimal::add);
			}
		}
		return resolved;
	}

	/**
	 * Translates the one payment-write violation a client can act on. A {@code uq_payment_idempotency}
	 * hit means the key already produced a POSTED payment and its {@code idempotency_keys} row has since
	 * been cleaned up (otherwise {@code IdempotencyService} would have rejected the reuse first); the key
	 * is spent, so this is an expired-key rejection (ADR-017), never retried by
	 * {@code PaymentConflictClassifier}. Anything else keeps its original type and is handled centrally.
	 */
	private static RuntimeException translatePaymentViolation(DataIntegrityViolationException ex) {
		Throwable cause = ex.getMostSpecificCause();
		String message = cause == null ? null : cause.getMessage();
		if (message != null && message.contains(PAYMENT_IDEMPOTENCY_CONSTRAINT)) {
			LOGGER.warn("payment create rejected by {}", PAYMENT_IDEMPOTENCY_CONSTRAINT);
			return new IdempotencyKeyExpiredException(
					"Idempotency-Key was already used for a payment create request");
		}
		return ex;
	}

	/**
	 * The journal of a received payment (TS §3 &quot;Terima payment regular&quot;): cash in, and one
	 * credit per component the payment actually resolved — {@code PIUTANG_DENDA}, {@code PIUTANG_BUNGA},
	 * {@code PIUTANG_POKOK} and, for the excess, {@code TITIPAN_NASABAH} (customer credit, PRD P-4).
	 * Components the payment did not reach contribute no line, so the entry still balances: the credits
	 * always add up to the cash received (invariant 6 &rarr; invariant 1).
	 *
	 * <p>Every line carries the contract, so reconciliation can group receivable against installments by
	 * contract (Addendum §7.1, check C).
	 */
	private static List<LedgerPostingLine> journalLines(Payment payment, ContractReceivableSnapshot snapshot,
			AllocationResult allocation) {
		UUID contractId = snapshot.contractId();
		List<LedgerPostingLine> lines = new ArrayList<>();
		lines.add(LedgerPostingLine.debit(LedgerAccount.KAS, payment.getAmount(), contractId));
		for (AllocationType type : CREDIT_ORDER) {
			BigDecimal credit = amountAllocatedTo(allocation, type);
			if (credit.signum() > 0) {
				lines.add(LedgerPostingLine.credit(accountOf(type), credit, contractId));
			}
		}
		return lines;
	}

	private static LedgerAccount accountOf(AllocationType type) {
		return switch (type) {
			case PENALTY -> LedgerAccount.PIUTANG_DENDA;
			case INTEREST -> LedgerAccount.PIUTANG_BUNGA;
			case PRINCIPAL -> LedgerAccount.PIUTANG_POKOK;
			case EXCESS -> LedgerAccount.TITIPAN_NASABAH;
		};
	}

	private static BigDecimal amountAllocatedTo(AllocationResult allocation, AllocationType type) {
		return allocation.lines().stream()
				.filter(line -> line.type() == type)
				.map(AllocationLine::amount)
				.reduce(BigDecimal.ZERO, BigDecimal::add)
				.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
	}

	/**
	 * Validates the payload and normalizes the amount to scale-2 money.
	 *
	 * <p>The field annotations cover nulls at the HTTP boundary; the aggregated rules an annotation
	 * cannot express (money scale, positivity) are checked here as well, exactly like contract creation
	 * (story B5), so a bad request is 400 {@code VALIDATION_ERROR} instead of a 500 out of the engine. A
	 * value finer than scale 2 is rejected rather than rounded (TS §2.1).
	 */
	private static BigDecimal requireAmount(PaymentRequest request) {
		if (request == null || request.contractId() == null || request.amount() == null
				|| request.channel() == null) {
			throw new BadRequestException("contract_id, amount and channel are required");
		}
		BigDecimal amount = request.amount();
		// Bound magnitude and scale before setScale (ADR-016, CWE-400): the scale() check below only catches
		// too-fine values, not a compact scientific-notation magnitude like 1e100000000, whose negative scale
		// passes scale() <= 2 and then expands into a ~100M-digit integer in setScale. This cheap check rejects
		// it as 400 first.
		DecimalBounds.requireMoneyDomain(amount, "amount");
		if (amount.scale() > MONEY_SCALE) {
			throw new BadRequestException("amount must be money with at most " + MONEY_SCALE
					+ " decimal places");
		}
		if (amount.signum() <= 0) {
			throw new BadRequestException("amount must be > 0");
		}
		return amount.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
	}

	/**
	 * Canonical request JSON for the retry fingerprint (TS §2.5): the fields that define <i>which</i>
	 * payment this is. Only these are compared, so a retry that merely reorders JSON keys is still the
	 * same request.
	 */
	private static String canonicalize(PaymentRequest request, BigDecimal amount) {
		Map<String, String> fields = CanonicalRequestJson.fields();
		fields.put("contract_id", request.contractId().toString());
		fields.put("amount", CanonicalRequestJson.money(amount));
		fields.put("channel", CanonicalRequestJson.name(request.channel()));
		return CanonicalRequestJson.render(fields);
	}
}
