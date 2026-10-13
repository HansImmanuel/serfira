package com.serfira.payment.application;

import com.serfira.contract.application.PaymentVoidPort;
import com.serfira.contract.application.VoidAffectedInstallment;
import com.serfira.contract.application.VoidEligibility;
import com.serfira.contract.application.ContractCreditPort;
import com.serfira.contract.domain.InstallmentStatus;
import com.serfira.ledger.application.LedgerPostingService;
import com.serfira.ledger.domain.LedgerRefType;
import com.serfira.payment.api.PaymentResponse;
import com.serfira.payment.api.VoidPaymentRequest;
import com.serfira.payment.domain.AllocationType;
import com.serfira.payment.domain.Payment;
import com.serfira.payment.domain.PaymentAllocation;
import com.serfira.payment.domain.PaymentStatus;
import com.serfira.payment.infrastructure.EarliestResolvingInstant;
import com.serfira.payment.infrastructure.PaymentAllocationRepository;
import com.serfira.payment.infrastructure.PaymentRepository;
import com.serfira.payment.infrastructure.ResolvedInstallmentTotal;
import com.serfira.shared.clock.Clock;
import com.serfira.shared.error.PaymentNotVoidableException;
import com.serfira.shared.idempotency.CanonicalRequestJson;
import com.serfira.shared.idempotency.IdempotencyService;
import com.serfira.shared.idempotency.IdempotentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The payment void use case: reverse a POSTED payment (E4, task T16, ADR-020). A void is a reversal, never
 * a deletion — the payment flips {@code POSTED → VOIDED}, a mirrored reversal journal is posted, the
 * affected installments are recomputed from the remaining POSTED payments, and the payment's own EXCESS
 * credit is reversed when it is still entirely AVAILABLE — all in one transaction.
 *
 * <p><b>One transaction per use case</b> (TS §2.3, ADR-020 D6): the status flip, reversal journal,
 * installment recomputation and credit reversal commit or roll back together. The idempotency claim is
 * taken inside this transaction ({@code IdempotencyService} is {@code Propagation.MANDATORY}), so a failed
 * attempt leaves no spent key and a retry can succeed.
 *
 * <p><b>Module boundaries.</b> This service owns {@code payment} and never touches {@code installment} or
 * {@code contract_credit}. It computes <b>what the remaining POSTED payments resolve</b> from its own
 * {@code payment_allocation} rows, asks {@code contract} to recompute the installments and reopen the
 * contract if a MATURITY close is undone ({@link PaymentVoidPort}), asks {@code contract} to reverse the
 * credit ({@link ContractCreditPort}), and posts the reversal through {@code ledger}
 * ({@link LedgerPostingService#postReversal}).
 *
 * <p><b>Guards (ADR-020 D4/D5), inside the transaction under row locks.</b> The eligibility guard (D5)
 * runs over <b>every</b> installment the payment's allocations reference: any {@code SETTLED}/
 * {@code WRITTEN_OFF} installment rejects the void (409 {@code PAYMENT_NOT_VOIDABLE}), as does a contract
 * that is neither {@code ACTIVE} nor {@code CLOSED}/{@code MATURITY} (voiding the final payment of a
 * matured contract reopens it, ADR-020 D1.3; a {@code SETTLEMENT} close or write-off is still rejected).
 * The consumed-credit guard (D4) locks the {@code contract_credit} row before reading its balance, so a
 * concurrent credit-apply/settlement cannot race it.
 */
@Service
public class PaymentVoidApplicationService {

	private static final Logger LOGGER = LoggerFactory.getLogger(PaymentVoidApplicationService.class);

	/** Idempotency scope of the void endpoint (TS §2.2: keys are endpoint-scoped). */
	static final String VOID_ENDPOINT = "POST /api/v1/payments/{id}/void";

	private final PaymentRepository payments;
	private final PaymentAllocationRepository allocations;
	private final PaymentVoidPort contractVoid;
	private final ContractCreditPort contractCredit;
	private final LedgerPostingService ledger;
	private final IdempotencyService idempotency;
	private final Clock clock;

	public PaymentVoidApplicationService(PaymentRepository payments, PaymentAllocationRepository allocations,
			PaymentVoidPort contractVoid, ContractCreditPort contractCredit, LedgerPostingService ledger,
			IdempotencyService idempotency, Clock clock) {
		this.payments = payments;
		this.allocations = allocations;
		this.contractVoid = contractVoid;
		this.contractCredit = contractCredit;
		this.ledger = ledger;
		this.idempotency = idempotency;
		this.clock = clock;
	}

	/**
	 * Voids one payment. Requires the client's {@code Idempotency-Key}: an identical retry replays the
	 * stored response (same voided payment, no second reversal), and the same key with a different payment
	 * id is 409 (TS §2.5). An already-VOIDED payment under the same key replays; a fresh void of an
	 * already-VOIDED payment is a no-op that returns the current (voided) representation.
	 */
	@Transactional
	public PaymentResponse voidPayment(UUID paymentId, String idempotencyKey, VoidPaymentRequest request) {
		String key = idempotencyKey == null ? null : idempotencyKey.trim();
		String reason = request == null ? null : request.reason();
		IdempotentResult<PaymentResponse> result = idempotency.execute(
				VOID_ENDPOINT, key, canonicalize(paymentId), PaymentResponse.class,
				() -> reverse(paymentId, reason));
		return result.response();
	}

	/**
	 * The mutation: flip the payment to VOIDED, reverse its journal, recompute the affected installments
	 * from the remaining POSTED payments, and reverse the payment's credit when it is entirely AVAILABLE.
	 * Runs inside the idempotency claim and the caller's transaction, so a replay skips all of it and any
	 * later failure rolls everything back together.
	 */
	private PaymentResponse reverse(UUID paymentId, String reason) {
		Payment payment = payments.findById(paymentId)
				.orElseThrow(() -> new PaymentNotFoundException(paymentId));
		List<PaymentAllocation> paymentAllocations = allocations.findByPaymentId(paymentId);

		if (payment.getStatus() == PaymentStatus.VOIDED) {
			// Already voided (e.g. a non-idempotent re-void, or a replay whose stored response is gone): the
			// reversal has already happened, so return the current representation without reversing twice.
			LOGGER.info("Payment {} is already VOIDED; void is a no-op", payment.getPaymentNo());
			return PaymentResponse.fromVoided(payment, paymentAllocations);
		}

		UUID contractId = payment.getContractId();
		Set<UUID> affectedInstallmentIds = affectedInstallmentIds(paymentAllocations);

		// D5: reject the whole void if any affected installment is terminal or the contract is not ACTIVE.
		assertVoidable(payment, contractId, affectedInstallmentIds);

		OffsetDateTime voidedAt = clock.now();

		// 1. Flip POSTED -> VOIDED and flush, so the recompute's "remaining POSTED" query already excludes
		//    this payment's allocations.
		payment.markVoided(voidedAt, reason);
		payments.saveAndFlush(payment);

		// 2. Recompute each affected installment from what the remaining POSTED payments now resolve, and
		//    reopen the contract if the void undid its MATURITY close. The earliest-instant map lets the
		//    contract set each installment's paid_at to its first surviving resolving payment.
		Map<UUID, BigDecimal> recomputedPaid = remainingResolvedByInstallment(affectedInstallmentIds);
		Map<UUID, OffsetDateTime> earliestResolving = earliestResolvingByInstallment(affectedInstallmentIds);
		contractVoid.applyVoidRecomputation(contractId, recomputedPaid, earliestResolving, voidedAt);

		// 3. Reverse the payment's EXCESS credit when it is still entirely AVAILABLE; reject (409) otherwise.
		Set<UUID> excessAllocationIds = excessAllocationIds(paymentAllocations);
		contractCredit.reverseAvailableCreditFromAllocations(excessAllocationIds, voidedAt);

		// 4. Post the one mirrored reversal journal (reuses the original PAYMENT (ref_type, ref_id)).
		ledger.postReversal(LedgerRefType.PAYMENT, payment.getId(), voidedAt,
				"Void of payment " + payment.getPaymentNo());

		LOGGER.info("Voided payment {} (id={}, contract={})", payment.getPaymentNo(), payment.getId(), contractId);
		return PaymentResponse.fromVoided(payment, paymentAllocations);
	}

	private void assertVoidable(Payment payment, UUID contractId, Set<UUID> affectedInstallmentIds) {
		VoidEligibility eligibility = contractVoid.checkVoidEligibility(contractId, affectedInstallmentIds);
		// D5/D1.3: a void may proceed against an ACTIVE contract, or a CLOSED/MATURITY one whose auto-close
		// the recompute can undo (reopenIfNoLongerMatured). A SETTLEMENT close or a TERMINATED/write-off is
		// rejected — reversing those closures is out of T16's scope.
		if (!eligibility.contractAllowsVoid()) {
			String closure = eligibility.closedReason() == null ? "" : "/" + eligibility.closedReason();
			throw new PaymentNotVoidableException("payment " + payment.getPaymentNo() + " cannot be voided: its "
					+ "contract is " + eligibility.contractStatus() + closure
					+ ", not ACTIVE or CLOSED/MATURITY");
		}
		for (VoidAffectedInstallment installment : eligibility.affectedInstallments()) {
			if (installment.status() == InstallmentStatus.SETTLED
					|| installment.status() == InstallmentStatus.WRITTEN_OFF) {
				throw new PaymentNotVoidableException("payment " + payment.getPaymentNo()
						+ " cannot be voided: an installment it resolved is " + installment.status());
			}
		}
	}

	/** Installments this payment's non-EXCESS allocations resolved, in a stable order. */
	private static Set<UUID> affectedInstallmentIds(List<PaymentAllocation> paymentAllocations) {
		Set<UUID> ids = new LinkedHashSet<>();
		for (PaymentAllocation allocation : paymentAllocations) {
			if (allocation.getType() != AllocationType.EXCESS && allocation.getInstallmentId() != null) {
				ids.add(allocation.getInstallmentId());
			}
		}
		return ids;
	}

	/** The EXCESS allocation ids whose customer credit the void must reverse (ADR-020 D4). */
	private static Set<UUID> excessAllocationIds(List<PaymentAllocation> paymentAllocations) {
		Set<UUID> ids = new LinkedHashSet<>();
		for (PaymentAllocation allocation : paymentAllocations) {
			if (allocation.getType() == AllocationType.EXCESS) {
				ids.add(allocation.getId());
			}
		}
		return ids;
	}

	/**
	 * Absolute resolved amount per affected installment from the remaining POSTED payments (the voided
	 * payment is already flipped VOIDED and flushed, so it is excluded). An installment that no remaining
	 * POSTED payment resolves is intentionally absent, which the contract recompute reads as paid = 0.
	 */
	private Map<UUID, BigDecimal> remainingResolvedByInstallment(Set<UUID> affectedInstallmentIds) {
		Map<UUID, BigDecimal> resolved = new LinkedHashMap<>();
		// Seed every affected installment with zero, so one fully reversed by the void is reset to 0 even
		// though it has no remaining POSTED allocation row.
		for (UUID installmentId : affectedInstallmentIds) {
			resolved.put(installmentId, BigDecimal.ZERO);
		}
		for (ResolvedInstallmentTotal total : allocations.sumResolvedByInstallmentIds(PaymentStatus.POSTED,
				affectedInstallmentIds)) {
			resolved.put(total.installmentId(), total.amount());
		}
		return resolved;
	}

	/**
	 * The earliest {@code paid_at} among the remaining POSTED payments that resolve each affected
	 * installment (the voided payment is already flipped VOIDED and flushed, so it is excluded). An
	 * installment with no remaining POSTED payment is absent, which the contract recompute treats as
	 * "no payment resolves this" (it then uses surviving credit's instant, or clears paid_at).
	 */
	private Map<UUID, OffsetDateTime> earliestResolvingByInstallment(Set<UUID> affectedInstallmentIds) {
		Map<UUID, OffsetDateTime> earliest = new LinkedHashMap<>();
		for (EarliestResolvingInstant instant : allocations.earliestResolvingInstantByInstallmentIds(
				PaymentStatus.POSTED, affectedInstallmentIds)) {
			earliest.put(instant.installmentId(), instant.paidAt());
		}
		return earliest;
	}

	/**
	 * Canonical request JSON for the retry fingerprint (TS §2.5): only the payment id identifies which void
	 * this is. The reason is deliberately excluded, so a retry with a different reason is still the same
	 * void (the first one wins) rather than a 409 body mismatch.
	 */
	private static String canonicalize(UUID paymentId) {
		Map<String, String> fields = CanonicalRequestJson.fields();
		fields.put("payment_id", paymentId.toString());
		return CanonicalRequestJson.render(fields);
	}
}
