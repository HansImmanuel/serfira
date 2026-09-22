package com.serfira.payment.application;

import com.serfira.contract.application.ContractReceivableSnapshot;
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
import com.serfira.payment.domain.PaymentChannel;
import com.serfira.payment.domain.PaymentStatus;
import com.serfira.payment.domain.allocation.AllocationLine;
import com.serfira.payment.domain.allocation.AllocationResult;
import com.serfira.payment.domain.allocation.InstallmentAllocationInput;
import com.serfira.payment.domain.allocation.PaymentAllocationEngine;
import com.serfira.payment.infrastructure.ActiveAllocationTotal;
import com.serfira.payment.infrastructure.PaymentAllocationRepository;
import com.serfira.payment.infrastructure.PaymentRepository;
import com.serfira.shared.clock.Clock;
import com.serfira.shared.document.DocumentNumberGenerator;
import com.serfira.shared.document.DocumentType;
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
 * {@code payment_allocation} and never touches {@code installment}. It reads a receivable snapshot
 * through {@link InstallmentReceivablePort} and hands the engine's resolved amounts back, and it posts
 * the journal through the {@code ledger} module's application service.
 *
 * <p>Scope: money received is recorded and allocated. Voiding a payment (E4) and turning EXCESS into a
 * {@code contract_credit} row with an application history (E3) are later stories — here an excess is
 * recorded as an EXCESS allocation and credited to {@code TITIPAN_NASABAH}, which is the complete
 * accounting of the money event.
 */
@Service
public class PaymentApplicationService {

	private static final Logger LOGGER = LoggerFactory.getLogger(PaymentApplicationService.class);

	/** Idempotency scope of the payment endpoint (TS §2.2: keys are endpoint-scoped). */
	static final String CREATE_ENDPOINT = "POST /api/v1/payments";

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
	private final DocumentNumberGenerator documentNumbers;
	private final IdempotencyService idempotency;
	private final LedgerPostingService ledger;
	private final Clock clock;

	public PaymentApplicationService(PaymentRepository payments, PaymentAllocationRepository allocations,
			InstallmentReceivablePort receivables, DocumentNumberGenerator documentNumbers,
			IdempotencyService idempotency, LedgerPostingService ledger, Clock clock) {
		this.payments = payments;
		this.allocations = allocations;
		this.receivables = receivables;
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
	 * The mutation itself: read the receivable, allocate, persist, resolve installments, post the journal.
	 * Runs inside the idempotency claim, inside the caller's transaction.
	 */
	private PaymentResponse receive(String idempotencyKey, PaymentRequest request, BigDecimal amount) {
		ContractReceivableSnapshot snapshot = receivables.loadReceivableSnapshot(request.contractId());
		if (snapshot.status() != ContractStatus.ACTIVE) {
			// The port already refuses anything else; the same invariant is asserted again where money is
			// written, so a future caller cannot resolve money against a closed contract by accident.
			throw new ContractStateException("contract " + snapshot.contractNo() + " is " + snapshot.status()
					+ " and cannot receive money; only an ACTIVE contract can");
		}

		AllocationResult allocation =
				ALLOCATION_ENGINE.allocate(amount, clock.today(), toAllocationInputs(snapshot));

		OffsetDateTime paidAt = clock.now();
		Payment payment = new Payment(documentNumbers.next(DocumentType.PAYMENT), snapshot.contractId(), amount,
				request.channel(), paidAt, idempotencyKey);
		payments.saveAndFlush(payment);
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
