package com.serfira.contract.domain.writeoff;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Caps the recognized receivable of a contract's open installments for a write-off (F5, task T19, DM
 * §1.12). Pure: no Spring, JPA or Clock, so the per-component split is unit-testable in isolation.
 *
 * <p>A write-off closes a contract and gives up collection of what is <b>recognized</b> but unpaid. Per
 * open installment (not SETTLED/WRITTEN_OFF) it keeps the same component residual the settlement waterfall
 * uses (ADR-018 D5), so the write-off clears exactly the receivable the ledger carries as
 * {@code PIUTANG_*}:
 *
 * <pre>
 * penalty   = effectivePenalty                                   (invariant 9 — net of adjustment + paid)
 * interest  = max(0, recognizedInterest − resolvedBeyondPenalty) (billed-but-unpaid interest)
 * principal = max(0, principalAmount − resolvedBeyondInterest)   (unpaid principal)
 * </pre>
 *
 * where {@code resolvedBeyondInterest = max(0, resolvedBeyondPenalty − recognizedInterest)}. Future
 * (unrecognized) interest is never written off — it was never a receivable (PRD §5A). The three component
 * amounts are the Cr {@code PIUTANG_*} lines of the one {@code WRITE_OFF} entry, and their sum per
 * installment is capped onto {@code written_off_amount} by {@code Installment.writeOff}; by construction it
 * never passes the installment's outstanding receivable (V3 {@code assert_installment_amounts} backstop).
 *
 * <p>Installments are processed oldest due first (due date is implied by the caller's period order; the
 * engine orders by period number for a deterministic, auditable result), but ordering does not change the
 * amounts — unlike a payment, a write-off caps every open installment independently rather than drawing
 * from a shared pool.
 */
public final class WriteOffEngine {

	private static final BigDecimal ZERO_MONEY = BigDecimal.ZERO.setScale(2);

	private WriteOffEngine() {
	}

	/**
	 * Builds the per-installment capped receivable for every open installment, in period order.
	 *
	 * @param installments the full schedule (SETTLED/WRITTEN_OFF installments are skipped)
	 * @return one {@link WriteOffLine} per open installment, oldest (lowest period) first; never null
	 */
	public static List<WriteOffLine> cap(List<WriteOffInstallmentInput> installments) {
		Objects.requireNonNull(installments, "installments");
		List<WriteOffInstallmentInput> ordered = new ArrayList<>(installments);
		ordered.sort(Comparator.comparingInt(WriteOffInstallmentInput::periodNo));

		List<WriteOffLine> lines = new ArrayList<>();
		for (WriteOffInstallmentInput installment : ordered) {
			if (installment.terminal()) {
				continue;
			}
			BigDecimal penalty = scale(installment.effectivePenalty());
			BigDecimal resolvedBeyondPenalty = scale(installment.resolvedBeyondPenalty());
			BigDecimal recognizedInterest = scale(installment.recognizedInterest());
			BigDecimal unpaidInterest = max0(recognizedInterest.subtract(resolvedBeyondPenalty));
			BigDecimal resolvedBeyondInterest = max0(resolvedBeyondPenalty.subtract(recognizedInterest));
			BigDecimal unpaidPrincipal = max0(scale(installment.principalAmount()).subtract(resolvedBeyondInterest));
			lines.add(new WriteOffLine(installment.installmentId(), installment.periodNo(), penalty, unpaidInterest,
					unpaidPrincipal));
		}
		return List.copyOf(lines);
	}

	private static BigDecimal scale(BigDecimal value) {
		return value == null ? ZERO_MONEY : value.setScale(2, java.math.RoundingMode.UNNECESSARY);
	}

	private static BigDecimal max0(BigDecimal value) {
		return value.signum() > 0 ? value : ZERO_MONEY;
	}
}
