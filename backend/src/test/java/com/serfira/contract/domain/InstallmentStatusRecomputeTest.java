package com.serfira.contract.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * E5 — {@link Installment#recomputeResolutionStatus(BigDecimal)}, the mutator the penalty waiver uses to
 * re-derive resolution status from the adjustment-aware balance after a write-down (task T15, ADR-019 D3).
 *
 * <p>Scope note: a freshly constructed installment exposes mutators for recognition, accrual and payment
 * only, so the states reachable here are PENDING → PARTIALLY_PAID → PAID. The
 * {@code SETTLED}/{@code WRITTEN_OFF} terminal-guard is defensive by construction (no public mutator moves
 * an installment into those states; settlement/write-off own them) and is exercised against the real
 * database elsewhere, exactly like {@code InstallmentPaymentResolutionTest}.
 */
class InstallmentStatusRecomputeTest {

	private static final OffsetDateTime PAID_AT =
			OffsetDateTime.of(2026, 9, 21, 9, 30, 0, 0, ZoneOffset.ofHours(7));

	private static final BigDecimal PRINCIPAL = new BigDecimal("1000.00");
	private static final BigDecimal INTEREST = new BigDecimal("100.00");
	private static final BigDecimal PENALTY = new BigDecimal("60.00");

	@Test
	void aWaiverClearingTheOnlyRemainingPenaltyFlipsThePartiallyPaidInstallmentToPaid() {
		Installment installment = paidPrincipalAndInterestWithPenalty();
		// Principal + interest are paid; the accrued penalty is the only remainder, so the payment path
		// (which ignores adjustments) left it PARTIALLY_PAID.
		assertThat(installment.getStatus()).isEqualTo(InstallmentStatus.PARTIALLY_PAID);

		boolean changed = installment.recomputeResolutionStatus(PENALTY);

		assertThat(changed).isTrue();
		assertThat(installment.getStatus()).isEqualTo(InstallmentStatus.PAID);
	}

	@Test
	void aPartialWaiverThatLeavesPenaltyOutstandingKeepsItPartiallyPaid() {
		Installment installment = paidPrincipalAndInterestWithPenalty();

		boolean changed = installment.recomputeResolutionStatus(new BigDecimal("20.00"));

		// 60.00 penalty − 20.00 waived = 40.00 still owed, so the installment is not yet resolved.
		assertThat(changed).isFalse();
		assertThat(installment.getStatus()).isEqualTo(InstallmentStatus.PARTIALLY_PAID);
	}

	@Test
	void recomputeDoesNotStampPaidAt() {
		Installment installment = paidPrincipalAndInterestWithPenalty();
		OffsetDateTime paidAtBefore = installment.getPaidAt();

		installment.recomputeResolutionStatus(PENALTY);

		// The waiver clears the installment to PAID but is not cash received, so paid_at keeps the first
		// resolving payment's instant and is never re-stamped by the recompute.
		assertThat(installment.getStatus()).isEqualTo(InstallmentStatus.PAID);
		assertThat(installment.getPaidAt()).isEqualTo(paidAtBefore);
	}

	@Test
	void aPendingInstallmentWithNothingPaidIsNotPromotedByAPartialWaiver() {
		// Only penalty accrued, nothing paid: a partial waiver that leaves penalty outstanding must not
		// report money arrived, so the installment stays PENDING rather than becoming PARTIALLY_PAID.
		Installment installment = installmentWithPenaltyOnly();
		assertThat(installment.getStatus()).isEqualTo(InstallmentStatus.PENDING);

		boolean changed = installment.recomputeResolutionStatus(new BigDecimal("20.00"));

		assertThat(changed).isFalse();
		assertThat(installment.getStatus()).isEqualTo(InstallmentStatus.PENDING);
	}

	@Test
	void aFullWaiverOnAPendingInstallmentWithNoPrincipalOrInterestResolvesItToPaid() {
		// Degenerate but legal: an installment whose only recognized amount is penalty, fully waived, has
		// nothing left outstanding and resolves to PAID.
		Installment installment = installmentWithPenaltyOnly();

		boolean changed = installment.recomputeResolutionStatus(PENALTY);

		assertThat(changed).isTrue();
		assertThat(installment.getStatus()).isEqualTo(InstallmentStatus.PAID);
	}

	@Test
	void aZeroAdjustmentRecomputeIsANoOpOnAlreadyResolvedState() {
		Installment installment = new Installment(contract(), 1, LocalDate.of(2026, 2, 28),
				PRINCIPAL, INTEREST);
		installment.recognizeInterest(INTEREST);
		installment.applyPayment(PRINCIPAL.add(INTEREST), PAID_AT);
		assertThat(installment.getStatus()).isEqualTo(InstallmentStatus.PAID);

		boolean changed = installment.recomputeResolutionStatus(BigDecimal.ZERO);

		assertThat(changed).isFalse();
		assertThat(installment.getStatus()).isEqualTo(InstallmentStatus.PAID);
	}

	@Test
	void aNullAdjustmentIsRejected() {
		Installment installment = paidPrincipalAndInterestWithPenalty();

		assertThatThrownBy(() -> installment.recomputeResolutionStatus(null))
				.isInstanceOf(NullPointerException.class);
	}

	/** Principal + recognized interest paid in full; {@value #PENALTY} penalty accrued and left unpaid. */
	private static Installment paidPrincipalAndInterestWithPenalty() {
		Installment installment = new Installment(contract(), 1, LocalDate.of(2026, 2, 28), PRINCIPAL, INTEREST);
		installment.recognizeInterest(INTEREST);
		installment.accruePenalty(PENALTY);
		installment.applyPayment(PRINCIPAL.add(INTEREST), PAID_AT);
		return installment;
	}

	/** Nothing paid; only {@value #PENALTY} penalty accrued (principal/interest unrecognized here). */
	private static Installment installmentWithPenaltyOnly() {
		Installment installment = new Installment(contract(), 1, LocalDate.of(2026, 2, 28),
				new BigDecimal("0.00"), new BigDecimal("0.00"));
		installment.accruePenalty(PENALTY);
		return installment;
	}

	/** Detached aggregate — enough for a pure value test; nothing is persisted here. */
	private static Contract contract() {
		Customer customer = new Customer("Budi Santoso", "3171012501900001", "nik-hash",
				"08123456789", "phone-lookup", "Jakarta");
		Asset asset = new Asset(AssetType.MOTORCYCLE, "Honda", "Beat", null, "B1234XY");
		return new Contract("MF-202601-0001", customer, asset, new BigDecimal("20000000.00"),
				new BigDecimal("16000000.00"), new BigDecimal("4000000.00"), 12, InterestScheme.FLAT,
				new BigDecimal("0.0150"), 3, new BigDecimal("0.0010"), LocalDate.of(2026, 1, 31), "idem-key");
	}
}
