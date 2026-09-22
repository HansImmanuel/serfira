package com.serfira.contract.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C3 — {@link Installment#applyPayment(BigDecimal, OffsetDateTime)}, the only mutator on the installment
 * aggregate: it raises {@code paid_amount}, stamps {@code paid_at} once, and derives the resolution state
 * from {@link InstallmentBalance} (DM §1.4, PRD P-3).
 *
 * <p>Scope note: a freshly constructed installment bills principal only (billing is story C4) and exposes
 * no mutator for the other resolution amounts, so the states reachable here are PENDING →
 * PARTIALLY_PAID → PAID. Penalty/interest/present-value combinations and the
 * {@code SETTLED}/{@code WRITTEN_OFF} branches are exercised through the real database in
 * {@code PaymentApiIT}, where the amounts can be seeded. The {@code SETTLED}/{@code WRITTEN_OFF} guard is
 * additionally defensive by construction: the allocation engine never resolves an installment whose
 * capacity is zero (ADR-009).
 */
class InstallmentPaymentResolutionTest {

	private static final OffsetDateTime FIRST_PAYMENT_AT =
			OffsetDateTime.of(2026, 9, 21, 9, 30, 0, 0, ZoneOffset.ofHours(7));
	private static final OffsetDateTime SECOND_PAYMENT_AT =
			OffsetDateTime.of(2026, 9, 22, 9, 30, 0, 0, ZoneOffset.ofHours(7));

	@Test
	void aResolutionCoveringTheWholeReceivableMarksTheInstallmentPaid() {
		Installment installment = installment();

		installment.applyPayment(new BigDecimal("1333333.33"), FIRST_PAYMENT_AT);

		assertThat(installment.getPaidAmount()).isEqualByComparingTo("1333333.33");
		assertThat(installment.getStatus()).isEqualTo(InstallmentStatus.PAID);
		assertThat(installment.getPaidAt()).isEqualTo(FIRST_PAYMENT_AT);
	}

	@Test
	void aPartialResolutionMarksTheInstallmentPartiallyPaidAndKeepsTheFirstPaidAt() {
		Installment installment = installment();

		installment.applyPayment(new BigDecimal("1000000.00"), FIRST_PAYMENT_AT);

		assertThat(installment.getStatus()).isEqualTo(InstallmentStatus.PARTIALLY_PAID);
		assertThat(installment.getPaidAt()).isEqualTo(FIRST_PAYMENT_AT);

		// The second payment completes the installment; paid_at stays the first resolving instant.
		installment.applyPayment(new BigDecimal("333333.33"), SECOND_PAYMENT_AT);

		assertThat(installment.getPaidAmount()).isEqualByComparingTo("1333333.33");
		assertThat(installment.getStatus()).isEqualTo(InstallmentStatus.PAID);
		assertThat(installment.getPaidAt()).isEqualTo(FIRST_PAYMENT_AT);
	}

	@Test
	void resolutionAmountsAccumulate() {
		Installment installment = installment();

		installment.applyPayment(new BigDecimal("100.00"), FIRST_PAYMENT_AT);
		installment.applyPayment(new BigDecimal("200.00"), SECOND_PAYMENT_AT);

		assertThat(installment.getPaidAmount()).isEqualByComparingTo("300.00");
	}

	@Test
	void anAmountFinerThanScaleTwoIsRejectedInsteadOfRounded() {
		Installment installment = installment();

		assertThatThrownBy(() -> installment.applyPayment(new BigDecimal("0.001"), FIRST_PAYMENT_AT))
				.isInstanceOf(ArithmeticException.class);
		assertThat(installment.getPaidAmount()).isEqualByComparingTo("0.00");
		assertThat(installment.getStatus()).isEqualTo(InstallmentStatus.PENDING);
	}

	@Test
	void aNonPositiveOrMissingResolutionIsRejected() {
		Installment installment = installment();

		assertThatThrownBy(() -> installment.applyPayment(new BigDecimal("0.00"), FIRST_PAYMENT_AT))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> installment.applyPayment(new BigDecimal("-1.00"), FIRST_PAYMENT_AT))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> installment.applyPayment(null, FIRST_PAYMENT_AT))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> installment.applyPayment(new BigDecimal("1.00"), null))
				.isInstanceOf(NullPointerException.class);
		assertThat(installment.getStatus()).isEqualTo(InstallmentStatus.PENDING);
	}

	private static Installment installment() {
		return new Installment(contract(), 1, LocalDate.of(2026, 2, 28),
				new BigDecimal("1333333.33"), new BigDecimal("240000.00"));
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
