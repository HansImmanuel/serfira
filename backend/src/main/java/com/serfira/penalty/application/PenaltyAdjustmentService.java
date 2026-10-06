package com.serfira.penalty.application;

import com.serfira.ledger.application.LedgerPostingService;
import com.serfira.ledger.domain.LedgerAccount;
import com.serfira.ledger.domain.LedgerPosting;
import com.serfira.ledger.domain.LedgerPostingLine;
import com.serfira.contract.application.InstallmentStatusRecomputePort;
import com.serfira.ledger.domain.LedgerRefType;
import com.serfira.penalty.domain.PenaltyAdjustment;
import com.serfira.penalty.domain.PenaltyAdjustmentType;
import com.serfira.penalty.infrastructure.PenaltyAdjustmentRepository;
import com.serfira.shared.audit.AuditContext;
import com.serfira.shared.clock.Clock;
import com.serfira.shared.error.BadRequestException;
import com.serfira.shared.error.ConflictException;
import com.serfira.shared.error.NotFoundException;
import com.serfira.shared.money.DecimalBounds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The penalty waive/reduce use case (E5, task T15, ADR-019 D2/D3): append an append-only
 * {@link PenaltyAdjustment} and post the correcting journal that removes the receivable the customer no
 * longer owes.
 *
 * <p><b>One transaction per use case</b> (TS §2.3, write-path order validate → compute → write aggregate →
 * post journal): the adjustment row and the one {@code PENALTY_WAIVER} journal entry either both commit or
 * neither does. The mapped 409 for a normal over-waive comes from the in-memory pre-check <em>before</em>
 * any save or flush. The V16 cap trigger and the V3 journal-balance trigger are {@code DEFERRABLE INITIALLY
 * DEFERRED}, so they fire at COMMIT, not on {@code flush}; they are the DB backstop that fails the commit if
 * an over-waive ever slips past the pre-check (e.g. a raw INSERT or a concurrent writer). {@code flush} here
 * only surfaces IMMEDIATE constraint failures earlier and is otherwise a no-op for these deferred triggers.
 *
 * <p><b>Module boundaries</b> (02_TECH_SPEC.md §1, ADR-001): this service lives in {@code penalty}, which
 * owns {@code penalty_adjustment}. It reads the contract's effective penalty through its own
 * {@link EffectivePenaltyPort} (which in turn reads {@code contract}-owned gross penalty through
 * {@link com.serfira.contract.application.InstallmentPenaltyPort}), keying everything on {@code contractId}
 * so no new {@code installment → contract} lookup edge is invented. The installment is confirmed to belong
 * to the request's contract via that snapshot. After writing the adjustment it asks {@code contract} to
 * re-derive the installment's resolution status and close the contract if it has matured, through the
 * {@code contract}-owned {@link InstallmentStatusRecomputePort} ({@code penalty → contract}, 02_TECH_SPEC.md
 * §1): {@code penalty} never touches the {@code installment} table itself.
 *
 * <p><b>Actor</b>: {@code approved_by} is the authenticated JWT {@code sub} bound in {@link AuditContext}
 * (Addendum §3.3/§3.4), never a request field. No PII or token is logged — only ids and safe metadata.
 */
@Service
public class PenaltyAdjustmentService {

	private static final Logger LOGGER = LoggerFactory.getLogger(PenaltyAdjustmentService.class);

	/** Money is scale-2 ({@code NUMERIC(19,2)}, TS §2.1). */
	private static final int MONEY_SCALE = 2;

	private final PenaltyAdjustmentRepository adjustments;
	private final EffectivePenaltyPort effectivePenalty;
	private final InstallmentStatusRecomputePort statusRecompute;
	private final LedgerPostingService ledger;
	private final AuditContext auditContext;
	private final Clock clock;

	public PenaltyAdjustmentService(PenaltyAdjustmentRepository adjustments, EffectivePenaltyPort effectivePenalty,
			InstallmentStatusRecomputePort statusRecompute, LedgerPostingService ledger, AuditContext auditContext,
			Clock clock) {
		this.adjustments = adjustments;
		this.effectivePenalty = effectivePenalty;
		this.statusRecompute = statusRecompute;
		this.ledger = ledger;
		this.auditContext = auditContext;
		this.clock = clock;
	}

	/**
	 * Waives or reduces recognized penalty on an installment.
	 *
	 * @param contractId     the installment's contract
	 * @param installmentId  the installment whose effective penalty to reduce
	 * @param type           WAIVE (full) or REDUCE (partial)
	 * @param amount         the write-down, positive scale-2 money
	 * @param reason         non-blank justification kept for audit
	 * @return the recorded adjustment and the installment's effective penalty afterwards
	 * @throws BadRequestException if the amount is not positive scale-2 money, or the reason is blank (400)
	 * @throws com.serfira.contract.application.ContractNotFoundException if no contract has that id (404)
	 * @throws com.serfira.contract.domain.ContractStateException        if the contract is not ACTIVE (409)
	 * @throws NotFoundException   if the installment is not part of the contract's schedule (404)
	 * @throws ConflictException   if the amount exceeds the installment's remaining effective penalty
	 *                             (gross − adjustments − paid penalty, 409), which would drive effective
	 *                             penalty negative (invariant 9) — writes nothing
	 */
	@Transactional
	public PenaltyAdjustmentResult adjust(UUID contractId, UUID installmentId, PenaltyAdjustmentType type,
			BigDecimal amount, String reason) {
		Objects.requireNonNull(contractId, "contractId");
		Objects.requireNonNull(installmentId, "installmentId");
		if (type == null) {
			throw new BadRequestException("adjustment_type is required");
		}
		if (reason == null || reason.isBlank()) {
			throw new BadRequestException("reason is required");
		}
		BigDecimal normalizedAmount = normalizeAmount(amount);

		InstallmentEffectivePenalty installment = effectiveFor(contractId, installmentId);
		if (normalizedAmount.compareTo(installment.effective()) > 0) {
			// Over-waive: valid request, conflicts with current state. installment.effective() is the
			// remaining effective penalty (gross − adjustments − paid penalty, ADR-019 Context), so the
			// cap already excludes penalty the customer has paid; waiving past it would drive the
			// receivable negative (invariant 9). 409, nothing written (mirrors the credit over-apply
			// precedent).
			throw new ConflictException("amount " + normalizedAmount + " exceeds the remaining effective penalty "
					+ installment.effective() + " of installment " + installmentId);
		}

		UUID approvedBy = auditContext.actorId();
		PenaltyAdjustment adjustment = adjustments.save(
				new PenaltyAdjustment(installmentId, type, normalizedAmount, reason, approvedBy));

		OffsetDateTime entryDate = clock.now();
		ledger.post(LedgerPosting.of(LedgerRefType.PENALTY_WAIVER, adjustment.getId(), entryDate,
				type + " penalty of installment " + installmentId,
				List.of(LedgerPostingLine.debit(LedgerAccount.BEBAN_WAIVER_DENDA, normalizedAmount, contractId),
						LedgerPostingLine.credit(LedgerAccount.PIUTANG_DENDA, normalizedAmount, contractId))));

		// Re-derive the installment's resolution status from the adjustment-aware balance and close the
		// contract as MATURITY when the waiver clears the last amount owed (F3, DM §3 invariant 17). The
		// contract module owns installment status, so this goes through its port. The versioned installment
		// write it performs is also what serializes concurrent waivers (F4): the retrying service above this
		// boundary retries the resulting OptimisticLockException.
		statusRecompute.recomputeAfterPenaltyAdjustment(contractId, installmentId);

		// Flush the pending writes (the adjustment, its journal, and the versioned installment update from the
		// recompute above) so any IMMEDIATE constraint failure surfaces here. The V16 cap trigger and the V3
		// journal-balance trigger are DEFERRABLE INITIALLY DEFERRED and still fire at COMMIT, not on this
		// flush; the normal over-waive is already rejected by the in-memory pre-check above, so those deferred
		// triggers only act as the DB backstop for a bypass or a concurrent over-waive at commit time.
		adjustments.flush();

		BigDecimal effectiveAfter = installment.effective().subtract(normalizedAmount)
				.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
		LOGGER.info("Recorded penalty {} adjustment {} of {} on installment {} "
				+ "(remaining effective (gross − adjustments − paid penalty) {} -> {})",
				type, adjustment.getId(), normalizedAmount, installmentId, installment.effective(), effectiveAfter);
		return new PenaltyAdjustmentResult(adjustment.getId(), installmentId, type, normalizedAmount, reason,
				approvedBy, adjustment.getCreatedAt(), effectiveAfter);
	}

	/** Validates the amount is positive scale-2 money, rejecting finer-than-scale-2 and non-positive as 400. */
	private BigDecimal normalizeAmount(BigDecimal amount) {
		if (amount == null) {
			throw new BadRequestException("amount is required");
		}
		DecimalBounds.requireMoneyDomain(amount, "amount");
		if (amount.scale() > MONEY_SCALE) {
			throw new BadRequestException("amount must be money with at most " + MONEY_SCALE + " decimal places");
		}
		if (amount.signum() <= 0) {
			throw new BadRequestException("amount must be > 0");
		}
		return amount.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
	}

	/** The effective penalty of one installment, confirming it belongs to the contract (404 otherwise). */
	private InstallmentEffectivePenalty effectiveFor(UUID contractId, UUID installmentId) {
		EffectivePenaltySnapshot snapshot = effectivePenalty.loadEffectivePenalty(contractId);
		return snapshot.installments().stream()
				.filter(installment -> installment.installmentId().equals(installmentId))
				.findFirst()
				.orElseThrow(() -> new NotFoundException("installment " + installmentId
						+ " is not part of contract " + contractId));
	}
}
