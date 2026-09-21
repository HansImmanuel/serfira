package com.serfira.payment.domain.allocation;

import com.serfira.payment.domain.AllocationType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C2 — shape and invariant 6 guards of {@link AllocationResult}: every posted payment has at least one
 * allocation and the lines total the payment amount (03_DOMAIN_MODEL.md §3 invariant 6, ADR-009).
 */
class AllocationResultTest {

	private static final UUID PERIOD_2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
	private static final UUID OTHER_INSTALLMENT = UUID.fromString("00000000-0000-0000-0000-000000000099");

	@Test
	void ofDerivesTheTotalAndTheExcess() {
		AllocationResult result = AllocationResult.of(List.of(
				line(AllocationType.INTEREST, PERIOD_2, "240000.00"),
				line(AllocationType.PRINCIPAL, PERIOD_2, "1333333.33"),
				line(AllocationType.EXCESS, null, "100.00")));

		assertThat(result.totalAllocated()).isEqualByComparingTo("1573433.33");
		assertThat(result.excessAmount()).isEqualByComparingTo("100.00");
		assertThat(result.amountFor(PERIOD_2, AllocationType.PRINCIPAL)).isEqualByComparingTo("1333333.33");
		assertThat(result.amountForInstallment(PERIOD_2)).isEqualByComparingTo("1573333.33");
	}

	@Test
	void aResultWithoutLinesIsRejected() {
		assertThatThrownBy(() -> new AllocationResult(List.of(), BigDecimal.ZERO, BigDecimal.ZERO))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("invariant 6");
	}

	@Test
	void linesThatDoNotTotalTheDeclaredAmountAreRejected() {
		assertThatThrownBy(() -> new AllocationResult(List.of(line(AllocationType.PRINCIPAL, PERIOD_2, "10.00")),
				BigDecimal.ZERO, new BigDecimal("20.00")))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("payment amount");
	}

	@Test
	void theDeclaredExcessMustMatchTheExcessLines() {
		assertThatThrownBy(() -> new AllocationResult(List.of(line(AllocationType.EXCESS, null, "10.00")),
				new BigDecimal("5.00"), new BigDecimal("10.00")))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("EXCESS lines");
	}

	@Test
	void theLineListIsCopiedAndImmutable() {
		List<AllocationLine> mutable = new ArrayList<>();
		mutable.add(line(AllocationType.PRINCIPAL, PERIOD_2, "10.00"));

		AllocationResult result = new AllocationResult(mutable, BigDecimal.ZERO, new BigDecimal("10.00"));
		mutable.clear();

		assertThat(result.lines()).hasSize(1);
		assertThatThrownBy(() -> result.lines().add(line(AllocationType.PRINCIPAL, PERIOD_2, "1.00")))
				.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void aNullLineIsRejected() {
		List<AllocationLine> withNull = new ArrayList<>();
		withNull.add(line(AllocationType.PRINCIPAL, PERIOD_2, "10.00"));
		withNull.add(null);

		assertThatThrownBy(() -> new AllocationResult(withNull, BigDecimal.ZERO, new BigDecimal("10.00")))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("non-null line");
	}

	@Test
	void helpersIgnoreOtherInstallmentsAndReturnScaleTwoZero() {
		AllocationResult result = AllocationResult.of(List.of(
				line(AllocationType.INTEREST, PERIOD_2, "240000.00"),
				line(AllocationType.EXCESS, null, "10.00")));

		assertThat(result.amountFor(PERIOD_2, AllocationType.PENALTY)).isEqualByComparingTo("0.00");
		assertThat(result.amountFor(PERIOD_2, AllocationType.PENALTY).scale()).isEqualTo(2);
		assertThat(result.amountForInstallment(OTHER_INSTALLMENT)).isEqualByComparingTo("0.00");
		assertThat(result.linesForInstallment(OTHER_INSTALLMENT)).isEmpty();
	}

	private static AllocationLine line(AllocationType type, UUID installmentRef, String amount) {
		return new AllocationLine(type, installmentRef, new BigDecimal(amount));
	}
}
