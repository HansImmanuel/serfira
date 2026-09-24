package com.serfira.contract.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D1 — {@link Installment#accruePenalty(BigDecimal)} and {@link InstallmentBalance#penaltyBase(Installment)},
 * the two additions the daily penalty step needs from the {@code contract} module (DM §1.9, TS §4.3,
 * PRD D-1, ADR-012).
 *
 * <p>Scope note: a freshly constructed installment has no mutator for {@code SETTLED} /
 * {@code WRITTEN_OFF}, so the defensive state guard of {@code accruePenalty} is exercised through the real
 * database in {@code PenaltyAccrualIT} — the same recipe {@code InstallmentRecognitionTest} and
 * {@code InstallmentBillingIT} use.
 */
class InstallmentPenaltyAccrualTest {

	private static final BigDecimal DAY_CHARGE = new BigDecimal("1573.33");
	private static final BigDecimal SCHEDULED_INTEREST = new BigDecimal("240000.00");
	private static final BigDecimal PERIOD_PRINCIPAL = new BigDecimal("1333333.33");
	private static final OffsetDateTime PAID_AT = OffsetDateTime.of(2026, 3, 5, 10, 0, 0, 0,
			ZoneOffset.ofHours(7));

	@Test
	void accrualRaisesTheGrossPenaltyAndTouchesNothingElse() {
		Installment installment = installment();

		installment.accruePenalty(DAY_CHARGE);

		assertThat(installment.getPenaltyAmount()).isEqualByComparingTo(DAY_CHARGE);
		// Recognizing denda is not a resolution of the installment: aging state and paid amounts stay.
		assertThat(installment.getStatus()).isEqualTo(InstallmentStatus.PENDING);
		assertThat(installment.getPaidAmount()).isEqualByComparingTo("0.00");
		assertThat(installment.getPaidAt()).isNull();
	}

	@Test
	void accrualAccumulatesOneDayAtATime() {
		Installment installment = installment();

		installment.accruePenalty(DAY_CHARGE);
		installment.accruePenalty(DAY_CHARGE);

		// The rows of penalty_accrual sum to this gross value (DM §1.9, TS §4.3).
		assertThat(installment.getPenaltyAmount()).isEqualByComparingTo("3146.66");
	}

	@Test
	void aNonPositiveMissingOrFinerAmountIsRejectedAndChangesNothing() {
		Installment installment = installment();

		assertThatThrownBy(() -> installment.accruePenalty(new BigDecimal("0.00")))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> installment.accruePenalty(new BigDecimal("-1.00")))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> installment.accruePenalty(null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> installment.accruePenalty(new BigDecimal("0.001")))
				.isInstanceOf(ArithmeticException.class);
		assertThat(installment.getPenaltyAmount()).isEqualByComparingTo("0.00");
	}

	@Test
	void thePenaltyBaseIsPrincipalPlusOnlyTheBilledInterest() {
		Installment installment = installment();

		// Future scheduled interest is not receivable, so it is not a penalty base either (PRD §5C).
		assertThat(InstallmentBalance.penaltyBase(installment)).isEqualByComparingTo(PERIOD_PRINCIPAL);

		installment.recognizeInterest(SCHEDULED_INTEREST);
		assertThat(InstallmentBalance.penaltyBase(installment)).isEqualByComparingTo("1573333.33");

		installment.applyPayment(new BigDecimal("1573333.33"), PAID_AT);
		assertThat(InstallmentBalance.penaltyBase(installment)).isEqualByComparingTo("0.00");
	}

	@Test
	void thePenaltyBaseNeverGrowsWithUnpaidPenaltyOrDropsBelowZero() {
		Installment installment = installment();
		installment.recognizeInterest(SCHEDULED_INTEREST);

		installment.accruePenalty(DAY_CHARGE);

		// Denda never accrues on denda (PRD D-1: "pokok+bunga"), so the base ignores penalty_amount.
		assertThat(InstallmentBalance.penaltyBase(installment)).isEqualByComparingTo("1573333.33");

		// A payment larger than the installment (excess) cannot make the base negative.
		installment.applyPayment(new BigDecimal("2000000.00"), PAID_AT);
		assertThat(InstallmentBalance.penaltyBase(installment)).isEqualByComparingTo("0.00");
	}

	@Test
	void anInstallmentPaidAtPrincipalOnlyStillHasABaseForItsBilledInterest() {
		Installment installment = installment();
		installment.applyPayment(PERIOD_PRINCIPAL, PAID_AT);
		assertThat(installment.getStatus()).isEqualTo(InstallmentStatus.PAID);

		installment.recognizeInterest(SCHEDULED_INTEREST);

		// ADR-011 decision 5: PAID is a cash marker, so the unpaid billed interest is still owed — and a
		// penalty may therefore still accrue on it (ADR-012 decision 2).
		assertThat(InstallmentBalance.penaltyBase(installment)).isEqualByComparingTo(SCHEDULED_INTEREST);
		assertThat(installment.getStatus()).isEqualTo(InstallmentStatus.PAID);
	}

	private static Installment installment() {
		return new Installment(contract(), 1, LocalDate.of(2026, 2, 28), PERIOD_PRINCIPAL, SCHEDULED_INTEREST);
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
