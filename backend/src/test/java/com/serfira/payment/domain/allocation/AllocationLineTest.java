package com.serfira.payment.domain.allocation;

import com.serfira.payment.domain.AllocationType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C2 — guards of {@link AllocationLine}, mirroring the {@code payment_allocation} CHECK constraints
 * (V1 {@code ck_payment_allocation_amount}, {@code ck_payment_allocation_excess}) and the "no rounding in
 * allocation" rule (ADR-009 decision 7).
 */
class AllocationLineTest {

	private static final UUID INSTALLMENT = UUID.fromString("00000000-0000-0000-0000-000000000002");

	@Test
	void anExcessLineCarriesNoInstallment() {
		AllocationLine line = new AllocationLine(AllocationType.EXCESS, null, new BigDecimal("100.00"));

		assertThat(line.installmentRef()).isNull();
		assertThat(line.amount()).isEqualByComparingTo("100.00");
	}

	@Test
	void excessWithAnInstallmentIsRejected() {
		assertThatThrownBy(() -> new AllocationLine(AllocationType.EXCESS, INSTALLMENT, new BigDecimal("100.00")))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("EXCESS");
	}

	@Test
	void anInstallmentComponentWithoutAnInstallmentIsRejected() {
		assertThatThrownBy(() -> new AllocationLine(AllocationType.PRINCIPAL, null, new BigDecimal("100.00")))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void zeroOrNegativeAmountsAreRejected() {
		assertThatThrownBy(() -> new AllocationLine(AllocationType.PRINCIPAL, INSTALLMENT, new BigDecimal("0.00")))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("> 0");
		assertThatThrownBy(() -> new AllocationLine(AllocationType.PRINCIPAL, INSTALLMENT, new BigDecimal("-0.01")))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void amountsAreNormalizedToScaleTwo() {
		AllocationLine line = new AllocationLine(AllocationType.PRINCIPAL, INSTALLMENT, new BigDecimal("1000"));

		assertThat(line.amount()).isEqualByComparingTo("1000.00");
		assertThat(line.amount().scale()).isEqualTo(2);
	}

	@Test
	void subCentAmountsAreRejectedInsteadOfRounded() {
		assertThatThrownBy(
				() -> new AllocationLine(AllocationType.PRINCIPAL, INSTALLMENT, new BigDecimal("100.001")))
				.isInstanceOf(ArithmeticException.class);
	}

	@Test
	void typeAndAmountAreRequired() {
		assertThatThrownBy(() -> new AllocationLine(null, INSTALLMENT, new BigDecimal("100.00")))
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new AllocationLine(AllocationType.PRINCIPAL, INSTALLMENT, null))
				.isInstanceOf(NullPointerException.class);
	}
}
