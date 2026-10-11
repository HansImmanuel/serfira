package com.serfira.settlement.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Produces the per-installment receivable resolution of a settlement (E2, task T13, ADR-018 D5). Pure: no
 * Spring, JPA or Clock, so the oldest-first waterfall is unit-testable against the same inputs the quote
 * engine prices (the resolution totals equal the quote's {@code penalty_outstanding} /
 * {@code unpaid_billed_interest} / {@code outstanding_principal} components by construction).
 *
 * <p>Each open installment (not SETTLED/WRITTEN_OFF) contributes, oldest due first (due date, tie-broken by
 * period number), one line per non-zero component in the waterfall order {@code PENALTY → INTEREST →
 * PRINCIPAL} (ADR-009):
 *
 * <pre>
 * penalty   = effectivePenalty                                   (invariant 9, D8)
 * interest  = max(0, recognizedInterest − resolvedBeyondPenalty) (billed-but-unpaid interest)
 * principal = max(0, principalAmount − resolvedBeyondInterest)   (unpaid principal)
 * </pre>
 *
 * where {@code resolvedBeyondPenalty} is the money already applied past penalty and
 * {@code resolvedBeyondInterest = max(0, resolvedBeyondPenalty − recognizedInterest)}. Future
 * (unrecognized) interest is never a receivable, so it is never a resolution line (PRD §5A, ADR-018 D5).
 * A component that nets to zero contributes no line (the {@code settlement_allocation} CHECK is
 * {@code amount > 0}).
 */
public final class SettlementResolutionEngine {

	private SettlementResolutionEngine() {
	}

	/**
	 * Builds the resolution lines, oldest installment first.
	 *
	 * @param installments the full schedule (future installments included; SETTLED/WRITTEN_OFF excluded)
	 * @return the resolution lines in oldest-first, PENALTY→INTEREST→PRINCIPAL order; never null
	 */
	public static List<SettlementResolutionLine> resolve(List<SettlementInstallmentInput> installments) {
		Objects.requireNonNull(installments, "installments");
		List<SettlementInstallmentInput> ordered = new ArrayList<>(installments);
		ordered.sort(Comparator.comparing(SettlementInstallmentInput::dueDate)
				.thenComparing(SettlementInstallmentInput::periodNo));

		List<SettlementResolutionLine> lines = new ArrayList<>();
		for (SettlementInstallmentInput installment : ordered) {
			if (installment.settled()) {
				continue;
			}
			BigDecimal penalty = installment.effectivePenalty();
			BigDecimal resolvedBeyondPenalty = installment.resolvedBeyondPenalty();
			BigDecimal unpaidInterest = max0(installment.recognizedInterest().subtract(resolvedBeyondPenalty));
			BigDecimal resolvedBeyondInterest = max0(resolvedBeyondPenalty.subtract(installment.recognizedInterest()));
			BigDecimal unpaidPrincipal = max0(installment.principalAmount().subtract(resolvedBeyondInterest));

			addLine(lines, installment, SettlementAllocationType.PENALTY, penalty);
			addLine(lines, installment, SettlementAllocationType.INTEREST, unpaidInterest);
			addLine(lines, installment, SettlementAllocationType.PRINCIPAL, unpaidPrincipal);
		}
		return List.copyOf(lines);
	}

	private static void addLine(List<SettlementResolutionLine> lines, SettlementInstallmentInput installment,
			SettlementAllocationType type, BigDecimal amount) {
		if (amount.signum() > 0) {
			lines.add(new SettlementResolutionLine(installment.installmentId(), type, amount));
		}
	}

	private static BigDecimal max0(BigDecimal value) {
		return value.signum() > 0 ? value : BigDecimal.ZERO.setScale(2);
	}

	/** Due date of the given installment input, for callers that need the resolution ordering key. */
	public static LocalDate dueDateOf(SettlementInstallmentInput installment) {
		return installment.dueDate();
	}
}
