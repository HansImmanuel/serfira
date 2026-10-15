package com.serfira.contract.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F5 — {@link Installment#writeOff(BigDecimal, OffsetDateTime)}, the terminal mutator a receivable
 * write-off uses to cap an installment's recognized receivable onto {@code written_off_amount} and move it
 * to {@code WRITTEN_OFF} (task T19, DM §1.12, invariant 15).
 *
 * <p>Mirrors {@link Installment#settle} but for the write-off terminal state: it is terminal, rejects a
 * prior {@code SETTLED}, and keeps the row coherent for the V4 {@code ck_installment_written_off_coherence}
 * CHECK (status WRITTEN_OFF ⇒ {@code written_off_at NOT NULL}).
 */
class InstallmentWriteOffTest {

	private static final OffsetDateTime WRITTEN_OFF_AT =
			OffsetDateTime.of(2026, 9, 21, 9, 30, 0, 0, ZoneOffset.ofHours(7));

	private static final BigDecimal PRINCIPAL = new BigDecimal("1000.00");
	private static final BigDecimal INTEREST = new BigDecimal("100.00");

	@Test
	void writingOffAnOpenInstallmentCapsTheReceivableAndMovesItTerminal() {
		Installment installment = pending();

		installment.writeOff(new BigDecimal("1100.00"), WRITTEN_OFF_AT);

		assertThat(installment.getWrittenOffAmount()).isEqualByComparingTo("1100.00");
		assertThat(installment.getWrittenOffAt()).isEqualTo(WRITTEN_OFF_AT);
		assertThat(installment.getStatus()).isEqualTo(InstallmentStatus.WRITTEN_OFF);
		assertThat(InstallmentBalance.of(installment).outstanding()).isEqualByComparingTo("0.00");
	}

	@Test
	void aZeroAmountStillMovesTheStatusButCreditsNothing() {
		// A fully-future installment with no recognized receivable: the write-off closes it with a 0 cap.
		Installment installment = pending();

		installment.writeOff(new BigDecimal("0.00"), WRITTEN_OFF_AT);

		assertThat(installment.getWrittenOffAmount()).isEqualByComparingTo("0.00");
		assertThat(installment.getWrittenOffAt()).isEqualTo(WRITTEN_OFF_AT);
		assertThat(installment.getStatus()).isEqualTo(InstallmentStatus.WRITTEN_OFF);
	}

	@Test
	void writingOffAfterAPartialPaymentCapsOnlyTheRemainder() {
		Installment installment = pending();
		installment.applyPayment(new BigDecimal("300.00"), WRITTEN_OFF_AT);

		// The command service caps at the remaining receivable (1100 − 300); the entity records what it is told.
		installment.writeOff(new BigDecimal("800.00"), WRITTEN_OFF_AT);

		assertThat(installment.getStatus()).isEqualTo(InstallmentStatus.WRITTEN_OFF);
		assertThat(installment.getPaidAmount()).isEqualByComparingTo("300.00");
		assertThat(installment.getWrittenOffAmount()).isEqualByComparingTo("800.00");
		assertThat(InstallmentBalance.of(installment).outstanding()).isEqualByComparingTo("0.00");
	}

	@Test
	void writingOffAnAlreadyWrittenOffInstallmentIsANoOp() {
		Installment installment = pending();
		installment.writeOff(new BigDecimal("1100.00"), WRITTEN_OFF_AT);

		installment.writeOff(new BigDecimal("500.00"), WRITTEN_OFF_AT.plusDays(1));

		// No second cap, no re-stamp.
		assertThat(installment.getWrittenOffAmount()).isEqualByComparingTo("1100.00");
		assertThat(installment.getWrittenOffAt()).isEqualTo(WRITTEN_OFF_AT);
	}

	@Test
	void aSettledInstallmentCannotBeWrittenOff() {
		Installment installment = pending();
		installment.settle(new BigDecimal("1100.00"), WRITTEN_OFF_AT);

		assertThatThrownBy(() -> installment.writeOff(new BigDecimal("1100.00"), WRITTEN_OFF_AT))
				.isInstanceOf(ContractStateException.class)
				.hasMessageContaining("SETTLED");
	}

	@Test
	void aNegativeAmountIsRejected() {
		Installment installment = pending();

		assertThatThrownBy(() -> installment.writeOff(new BigDecimal("-1.00"), WRITTEN_OFF_AT))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void aFinerThanScaleTwoAmountIsRejected() {
		Installment installment = pending();

		assertThatThrownBy(() -> installment.writeOff(new BigDecimal("1.001"), WRITTEN_OFF_AT))
				.isInstanceOf(ArithmeticException.class);
	}

	@Test
	void aNullWrittenOffInstantIsRejected() {
		Installment installment = pending();

		assertThatThrownBy(() -> installment.writeOff(new BigDecimal("1100.00"), null))
				.isInstanceOf(NullPointerException.class);
	}

	/** A PENDING installment with its interest recognized, so its full recognized receivable is 1100.00. */
	private static Installment pending() {
		Installment installment = new Installment(contract(), 1, LocalDate.of(2026, 2, 28), PRINCIPAL, INTEREST);
		installment.recognizeInterest(INTEREST);
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
