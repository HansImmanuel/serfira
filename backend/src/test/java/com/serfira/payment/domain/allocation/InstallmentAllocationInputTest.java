package com.serfira.payment.domain.allocation;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C2 — receivable arithmetic and guards of {@link InstallmentAllocationInput} (03_DOMAIN_MODEL.md §1.4,
 * §3 invariants 3/9/13; ADR-009 decision 3). These are the numbers the engine relies on, so they are pinned
 * against the documented formulas and the deferred V3 caps.
 */
class InstallmentAllocationInputTest {

	private static final UUID INSTALLMENT = UUID.fromString("00000000-0000-0000-0000-000000000002");
	private static final LocalDate DUE_DATE = LocalDate.of(2026, 3, 31);

	@Test
	void receivableArithmeticFollowsTheDomainModel() {
		InstallmentAllocationInput input = input("1333333.33", "240000.00", "10000.00", "4000.00", "1000.00",
				"40000.00", "100000.00", "0.00", "0.00");

		assertThat(input.effectivePenalty()).isEqualByComparingTo("6000.00");
		assertThat(input.recognizedTotal()).isEqualByComparingTo("1579333.33");
		assertThat(input.allocatedAmount()).isEqualByComparingTo("141000.00");
		assertThat(input.resolvedAmount()).isEqualByComparingTo("141000.00");
		assertThat(input.allocationCapacity()).isEqualByComparingTo("1438333.33");
		assertThat(input.penaltyHeadroom()).isEqualByComparingTo("5000.00");
		assertThat(input.interestHeadroom()).isEqualByComparingTo("200000.00");
		assertThat(input.principalHeadroom()).isEqualByComparingTo("1233333.33");
		assertThat(input.hasCapacity()).isTrue();
		assertThat(input.penaltyHeadroom().add(input.interestHeadroom()).add(input.principalHeadroom()))
				.isEqualByComparingTo(input.allocationCapacity());
	}

	@Test
	void settlementAndWriteOffConsumeCapacityUntilTheInstallmentIsResolved() {
		InstallmentAllocationInput partiallySettled = input("1333333.33", "240000.00", "0.00", "0.00", "0.00",
				"0.00", "0.00", "1000000.00", "0.00");

		assertThat(partiallySettled.resolvedAmount()).isEqualByComparingTo("1000000.00");
		assertThat(partiallySettled.allocationCapacity()).isEqualByComparingTo("573333.33");

		InstallmentAllocationInput fullyWrittenOff = input("1333333.33", "240000.00", "0.00", "0.00", "0.00",
				"0.00", "0.00", "0.00", "1573333.33");

		assertThat(fullyWrittenOff.allocationCapacity()).isEqualByComparingTo("0.00");
		assertThat(fullyWrittenOff.hasCapacity()).isFalse();
	}

	@Test
	void allocationsAboveAComponentCapAreRejected() {
		assertThatThrownBy(() -> input("1000.00", "0.00", "0.00", "0.00", "0.00", "0.00", "1000.01", "0.00", "0.00"))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("principal allocations");
		assertThatThrownBy(() -> input("1000.00", "100.00", "0.00", "0.00", "0.00", "100.01", "0.00", "0.00", "0.00"))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("interest allocations");
		assertThatThrownBy(() -> input("1000.00", "0.00", "100.00", "0.00", "100.01", "0.00", "0.00", "0.00", "0.00"))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("penalty allocations");
	}

	@Test
	void penaltyAdjustmentsAboveTheRecognizedPenaltyAreRejected() {
		assertThatThrownBy(() -> input("1000.00", "0.00", "100.00", "100.01", "0.00", "0.00", "0.00", "0.00", "0.00"))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("penalty adjustments");
	}

	@Test
	void resolvingBeyondTheRecognizedTotalIsRejected() {
		assertThatThrownBy(() -> input("1000.00", "0.00", "0.00", "0.00", "0.00", "0.00", "0.00", "0.00", "1000.01"))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("recognized total");
	}

	@Test
	void negativeAndSubCentAmountsAreRejected() {
		assertThatThrownBy(() -> input("-0.01", "0.00", "0.00", "0.00", "0.00", "0.00", "0.00", "0.00", "0.00"))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("principalAmount");
		assertThatThrownBy(() -> input("1000.001", "0.00", "0.00", "0.00", "0.00", "0.00", "0.00", "0.00", "0.00"))
				.isInstanceOf(ArithmeticException.class);
	}

	@Test
	void periodNumberMustBePositive() {
		assertThatThrownBy(() -> new InstallmentAllocationInput(INSTALLMENT, 0, DUE_DATE, dec("1000.00"), dec("0.00"),
				dec("0.00"), dec("0.00"), dec("0.00"), dec("0.00"), dec("0.00"), dec("0.00"), dec("0.00")))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("periodNo");
	}

	@Test
	void referenceAndDueDateAreRequired() {
		assertThatThrownBy(() -> new InstallmentAllocationInput(null, 1, DUE_DATE, dec("1000.00"), dec("0.00"),
				dec("0.00"), dec("0.00"), dec("0.00"), dec("0.00"), dec("0.00"), dec("0.00"), dec("0.00")))
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new InstallmentAllocationInput(INSTALLMENT, 1, null, dec("1000.00"), dec("0.00"),
				dec("0.00"), dec("0.00"), dec("0.00"), dec("0.00"), dec("0.00"), dec("0.00"), dec("0.00")))
				.isInstanceOf(NullPointerException.class);
	}

	private static InstallmentAllocationInput input(String principal, String recognizedInterest, String penalty,
			String adjustments, String allocatedPenalty, String allocatedInterest, String allocatedPrincipal,
			String settled, String writtenOff) {
		return new InstallmentAllocationInput(INSTALLMENT, 2, DUE_DATE, dec(principal), dec(recognizedInterest),
				dec(penalty), dec(adjustments), dec(allocatedPenalty), dec(allocatedInterest),
				dec(allocatedPrincipal), dec(settled), dec(writtenOff));
	}

	private static BigDecimal dec(String value) {
		return new BigDecimal(value);
	}
}
