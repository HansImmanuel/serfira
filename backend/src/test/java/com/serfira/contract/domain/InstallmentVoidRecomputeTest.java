package com.serfira.contract.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * E4 — {@link Installment#resetPaidAmountForVoid(BigDecimal, OffsetDateTime)}, the mutator a payment void
 * uses to reset an installment's {@code paid_amount} to the absolute total the remaining POSTED payments
 * (plus surviving applied credit) resolve, re-stamp {@code paid_at} from the first surviving resolving
 * payment, then re-derive the resolution status (task T16, ADR-020 D1.3).
 *
 * <p>Unlike {@link Installment#applyPayment}, this can <b>lower</b> the paid amount, so the status demotes
 * {@code PAID → PARTIALLY_PAID → PENDING}. The {@code SETTLED}/{@code WRITTEN_OFF} terminal guard is
 * defensive by construction (no public mutator reaches those states here) and the whole void is rejected
 * before it anyway (ADR-020 D5), so it is exercised against the real database in {@code PaymentVoidIT}.
 */
class InstallmentVoidRecomputeTest {

	private static final OffsetDateTime PAID_AT =
			OffsetDateTime.of(2026, 9, 21, 9, 30, 0, 0, ZoneOffset.ofHours(7));
	private static final OffsetDateTime LATER_PAID_AT =
			OffsetDateTime.of(2026, 9, 24, 11, 0, 0, 0, ZoneOffset.ofHours(7));

	private static final BigDecimal PRINCIPAL = new BigDecimal("1000.00");
	private static final BigDecimal INTEREST = new BigDecimal("100.00");

	@Test
	void resettingToZeroDemotesAFullyPaidInstallmentBackToPending() {
		Installment installment = fullyPaid();
		assertThat(installment.getStatus()).isEqualTo(InstallmentStatus.PAID);
		assertThat(installment.getPaidAt()).isEqualTo(PAID_AT);

		installment.resetPaidAmountForVoid(new BigDecimal("0.00"), null);

		// The only payment that resolved it is voided: paid_amount back to 0, paid_at cleared, PENDING.
		assertThat(installment.getPaidAmount()).isEqualByComparingTo("0.00");
		assertThat(installment.getPaidAt()).isNull();
		assertThat(installment.getStatus()).isEqualTo(InstallmentStatus.PENDING);
	}

	@Test
	void resettingToAPartialTotalDemotesPaidToPartiallyPaidAndKeepsPaidAt() {
		Installment installment = fullyPaid();

		// A second POSTED payment still resolves 400.00 of this installment after the void.
		installment.resetPaidAmountForVoid(new BigDecimal("400.00"), PAID_AT);

		assertThat(installment.getPaidAmount()).isEqualByComparingTo("400.00");
		assertThat(installment.getStatus()).isEqualTo(InstallmentStatus.PARTIALLY_PAID);
		assertThat(installment.getPaidAt()).isEqualTo(PAID_AT);
	}

	@Test
	void resettingToTheFullRecognizedTotalKeepsItPaid() {
		Installment installment = fullyPaid();

		// A void that only removed an over-resolution leaves the installment still fully paid.
		installment.resetPaidAmountForVoid(PRINCIPAL.add(INTEREST), PAID_AT);

		assertThat(installment.getStatus()).isEqualTo(InstallmentStatus.PAID);
		assertThat(installment.getPaidAt()).isEqualTo(PAID_AT);
	}

	@Test
	void voidingTheEarliestPaymentAdvancesPaidAtToTheNextSurvivingPayment() {
		Installment installment = fullyPaid();

		// The void removes the earliest payment (which supplied PAID_AT); a later POSTED payment still
		// resolves 400.00, so paid_at advances to that surviving payment's instant (ADR-020 D1.3, finding #4).
		installment.resetPaidAmountForVoid(new BigDecimal("400.00"), LATER_PAID_AT);

		assertThat(installment.getPaidAmount()).isEqualByComparingTo("400.00");
		assertThat(installment.getStatus()).isEqualTo(InstallmentStatus.PARTIALLY_PAID);
		assertThat(installment.getPaidAt()).isEqualTo(LATER_PAID_AT);
	}

	@Test
	void aNegativeRecomputedAmountIsRejected() {
		Installment installment = fullyPaid();

		assertThatThrownBy(() -> installment.resetPaidAmountForVoid(new BigDecimal("-1.00"), PAID_AT))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void aFinerThanScaleTwoAmountIsRejected() {
		Installment installment = fullyPaid();

		assertThatThrownBy(() -> installment.resetPaidAmountForVoid(new BigDecimal("1.001"), PAID_AT))
				.isInstanceOf(ArithmeticException.class);
	}

	@Test
	void aNullAmountIsRejected() {
		Installment installment = fullyPaid();

		assertThatThrownBy(() -> installment.resetPaidAmountForVoid(null, PAID_AT))
				.isInstanceOf(NullPointerException.class);
	}

	/** Principal + recognized interest paid in full, so the installment is PAID with paid_at stamped. */
	private static Installment fullyPaid() {
		Installment installment = new Installment(contract(), 1, LocalDate.of(2026, 2, 28), PRINCIPAL, INTEREST);
		installment.recognizeInterest(INTEREST);
		installment.applyPayment(PRINCIPAL.add(INTEREST), PAID_AT);
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
