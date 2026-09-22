package com.serfira.contract.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C4 — {@link Installment#recognizeInterest(BigDecimal)}, the recognition mutator (DM §1.4,
 * 04_GAPS_ADDENDUM.md §12, ADR-011): it raises {@code recognized_interest_amount} as receivable and
 * deliberately leaves the resolution state alone.
 *
 * <p>Scope note: a freshly constructed installment has no mutator for {@code SETTLED} /
 * {@code WRITTEN_OFF}, so the defensive state guard of {@code recognizeInterest} is exercised through
 * the real database in {@code InstallmentBillingIT} — the same recipe {@code InstallmentBalanceIT} uses
 * to reach the resolution states.
 */
class InstallmentRecognitionTest {

	private static final BigDecimal SCHEDULED_INTEREST = new BigDecimal("240000.00");
	private static final OffsetDateTime PAID_AT = OffsetDateTime.of(2026, 1, 10, 9, 30, 0, 0, ZoneOffset.ofHours(7));

	@Test
	void recognitionRaisesTheReceivableWithoutResolvingTheInstallment() {
		Installment installment = installment();

		installment.recognizeInterest(SCHEDULED_INTEREST);

		assertThat(installment.getRecognizedInterestAmount()).isEqualByComparingTo(SCHEDULED_INTEREST);
		// Recognition is an accounting fact about the schedule, not money received: nothing is resolved.
		assertThat(installment.getPaidAmount()).isEqualByComparingTo("0.00");
		assertThat(installment.getStatus()).isEqualTo(InstallmentStatus.PENDING);
		assertThat(installment.getPaidAt()).isNull();
	}

	@Test
	void recognitionAccumulatesInDeltas() {
		Installment installment = installment();

		installment.recognizeInterest(new BigDecimal("100000.00"));
		installment.recognizeInterest(new BigDecimal("140000.00"));

		assertThat(installment.getRecognizedInterestAmount()).isEqualByComparingTo(SCHEDULED_INTEREST);
	}

	@Test
	void recognitionPastTheScheduledInterestIsRejectedAndChangesNothing() {
		Installment installment = installment();
		installment.recognizeInterest(SCHEDULED_INTEREST);

		// DM §3 invariant 10: recognized_interest_amount <= interest_amount.
		assertThatThrownBy(() -> installment.recognizeInterest(new BigDecimal("0.01")))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("invariant 10");
		assertThat(installment.getRecognizedInterestAmount()).isEqualByComparingTo(SCHEDULED_INTEREST);
	}

	@Test
	void aNonPositiveOrMissingRecognitionIsRejected() {
		Installment installment = installment();

		assertThatThrownBy(() -> installment.recognizeInterest(new BigDecimal("0.00")))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> installment.recognizeInterest(new BigDecimal("-1.00")))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> installment.recognizeInterest(null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThat(installment.getRecognizedInterestAmount()).isEqualByComparingTo("0.00");
	}

	@Test
	void anAmountFinerThanScaleTwoIsRejectedInsteadOfRounded() {
		Installment installment = installment();

		assertThatThrownBy(() -> installment.recognizeInterest(new BigDecimal("0.001")))
				.isInstanceOf(ArithmeticException.class);
		assertThat(installment.getRecognizedInterestAmount()).isEqualByComparingTo("0.00");
	}

	@Test
	void anInstallmentAlreadyPaidAtPrincipalOnlyIsStillBillableAndKeepsItsPaidState() {
		Installment installment = installment();
		installment.applyPayment(new BigDecimal("1333333.33"), PAID_AT);
		assertThat(installment.getStatus()).isEqualTo(InstallmentStatus.PAID);

		installment.recognizeInterest(SCHEDULED_INTEREST);

		// ADR-011: recognition is not a resolution, so PAID (a cash marker) stays and the interest becomes
		// the receivable the customer still owes; the earlier overpayment is credit, not interest.
		assertThat(installment.getStatus()).isEqualTo(InstallmentStatus.PAID);
		assertThat(installment.getRecognizedInterestAmount()).isEqualByComparingTo(SCHEDULED_INTEREST);
		assertThat(InstallmentBalance.of(installment).outstanding()).isEqualByComparingTo("240000.00");
	}

	private static Installment installment() {
		return new Installment(contract(), 1, LocalDate.of(2026, 2, 28),
				new BigDecimal("1333333.33"), SCHEDULED_INTEREST);
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
