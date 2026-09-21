package com.serfira.contract.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link InstallmentBalance} (DM §1.4, §3 invariant 13).
 *
 * <p>Scope note: {@code Installment} exposes no mutators — resolution amounts are written by the
 * payment/settlement stories (C2/E1-E3) — so this pure test covers the states reachable from a
 * generated schedule plus the guards. Balances for PARTIALLY_PAID / PAID / SETTLED / WRITTEN_OFF are
 * covered against a real database in {@code InstallmentBalanceIT}, where the amounts can be driven
 * through JDBC and read back through the JPA mapping.
 */
class InstallmentBalanceTest {

	@Test
	void scheduledInstallmentRecognizesPrincipalOnly() {
		// A freshly generated installment bills principal but has NOT recognized its interest yet:
		// future scheduled interest is not receivable (TS §5, PRD §5A).
		InstallmentBalance balance = InstallmentBalance.of(installment("1333333.33", "240000.00"));

		assertThat(balance.recognizedTotal()).isEqualByComparingTo("1333333.33");
		assertThat(balance.resolvedAmount()).isEqualByComparingTo("0.00");
		assertThat(balance.outstanding()).isEqualByComparingTo("1333333.33");
		assertThat(balance.recognizedTotal()).isNotEqualByComparingTo("1573333.33");
	}

	@Test
	void zeroValuedInstallmentHasZeroBalance() {
		InstallmentBalance balance = InstallmentBalance.of(installment("0.00", "0.00"));

		assertThat(balance.recognizedTotal()).isEqualByComparingTo("0");
		assertThat(balance.resolvedAmount()).isEqualByComparingTo("0");
		assertThat(balance.outstanding()).isEqualByComparingTo("0");
	}

	@Test
	void noAdjustmentOverloadEqualsZeroAdjustment() {
		Installment installment = installment("1000000.00", "15000.00");

		assertThat(InstallmentBalance.of(installment))
				.isEqualTo(InstallmentBalance.of(installment, BigDecimal.ZERO));
	}

	@Test
	void negativePenaltyAdjustmentIsRejected() {
		Installment installment = installment("1000000.00", "15000.00");

		assertThatThrownBy(() -> InstallmentBalance.of(installment, new BigDecimal("-0.01")))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("penaltyAdjustments");
	}

	@Test
	void nullInstallmentIsRejected() {
		assertThatThrownBy(() -> InstallmentBalance.of(null))
				.isInstanceOf(NullPointerException.class);
	}

	private static Installment installment(String principalAmount, String interestAmount) {
		return new Installment(contract(), 1, LocalDate.of(2026, 2, 28),
				new BigDecimal(principalAmount), new BigDecimal(interestAmount));
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