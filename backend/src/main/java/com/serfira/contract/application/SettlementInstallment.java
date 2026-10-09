package com.serfira.contract.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Settlement-facing read view of one installment (E1 settlement quote, task T12, ADR-018 D2/D6/D7).
 *
 * <p>Why a dedicated record rather than reusing {@link InstallmentReceivable}: a settlement quote needs
 * the <b>scheduled</b> {@code interestAmount} (to price future, not-yet-recognized interest) in addition
 * to the <b>recognized</b> interest the receivable view exposes. Widening the payment-facing
 * {@link InstallmentReceivable} would leak a future-interest figure the allocation waterfall must never
 * see (future interest is never a receivable, PRD §5A). Keeping a separate seam keeps each view's meaning
 * unambiguous (ADR-018 Consequences, ADR-010-style seam addition).
 *
 * <p>Every amount is the installment's own column, unmodified and scale-2: the settlement quote engine
 * derives the component split (unpaid principal, unpaid billed interest, future interest) itself, so there
 * is exactly one definition of each figure and no second copy of the receivable rules here.
 *
 * @param installmentId            installment the amounts belong to
 * @param periodNo                 1-based period number, used to order oldest-first on equal due dates
 * @param dueDate                  due date; drives the active-period / future-period split (ADR-018 D7)
 * @param resolvedOutsidePayment   {@code true} if the installment is SETTLED/WRITTEN_OFF and must be
 *                                 excluded from pricing (ADR-018 D2, its receivable was resolved another
 *                                 way). The {@code contract} module derives this so the settlement module
 *                                 need not know the {@code InstallmentStatus} enum.
 * @param principalAmount          scheduled principal (source of unpaid principal)
 * @param interestAmount           scheduled interest for the period (source of future unrecognized interest)
 * @param recognizedInterestAmount interest billed/recognized so far (source of unpaid billed interest)
 * @param resolvedAmount           {@code paid + settled + written-off}, resolved across all components
 */
public record SettlementInstallment(UUID installmentId, int periodNo, LocalDate dueDate,
		boolean resolvedOutsidePayment, BigDecimal principalAmount, BigDecimal interestAmount,
		BigDecimal recognizedInterestAmount, BigDecimal resolvedAmount) {
}
