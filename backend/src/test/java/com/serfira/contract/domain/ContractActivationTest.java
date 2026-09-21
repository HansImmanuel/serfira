package com.serfira.contract.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the DRAFT → ACTIVE transition (PRD C-2, DM §1.3, ADR-006).
 *
 * <p>Coverage note: the CLOSED/TERMINATED rejection is exercised against a real database in
 * {@code ContractActivationIT}, because those statuses are only reachable through the write-off /
 * maturity stories, which drive the row directly.
 */
class ContractActivationTest {

	private static final LocalDate PLANNED_START = LocalDate.of(2026, 1, 31);
	private static final LocalDate EFFECTIVE_START = LocalDate.of(2026, 2, 5);

	@Test
	void activationPinsEffectiveDateAndConfigurationSnapshot() {
		Contract contract = draft();

		contract.activate(EFFECTIVE_START, 7, new BigDecimal("0.0020"));

		assertThat(contract.getStatus()).isEqualTo(ContractStatus.ACTIVE);
		assertThat(contract.getStartDate()).isEqualTo(EFFECTIVE_START);
		assertThat(contract.getGracePeriodDays()).isEqualTo(7);
		assertThat(contract.getPenaltyRateDaily()).isEqualByComparingTo("0.0020");
		assertThat(contract.getPlannedStartDate()).isEqualTo(PLANNED_START);
		assertThat(contract.isActive()).isTrue();
		assertThat(contract.isDraft()).isFalse();
	}

	@Test
	void draftKeepsEffectiveDateNullAndExposesThePlannedDate() {
		Contract contract = draft();

		assertThat(contract.isDraft()).isTrue();
		assertThat(contract.isActive()).isFalse();
		assertThat(contract.getStartDate()).isNull();
		assertThat(contract.getPlannedStartDate()).isEqualTo(PLANNED_START);
	}

	@Test
	void activationWithoutAnyStartDateIsRejectedBeforeScheduleGeneration() {
		Contract contract = draft();

		assertThatThrownBy(() -> contract.activate(null, 3, new BigDecimal("0.0010")))
				.isInstanceOf(ContractStateException.class)
				.hasMessageContaining("start date");
		assertThat(contract.getStatus()).isEqualTo(ContractStatus.DRAFT);
	}

	@Test
	void activationIsRejectedOnceTheContractIsActive() {
		Contract contract = draft();
		contract.activate(EFFECTIVE_START, 3, new BigDecimal("0.0010"));

		assertThatThrownBy(() -> contract.activate(EFFECTIVE_START, 3, new BigDecimal("0.0010")))
				.isInstanceOf(ContractStateException.class)
				.hasMessageContaining("ACTIVE");
		// The failed attempt must not have changed the pinned state.
		assertThat(contract.getStartDate()).isEqualTo(EFFECTIVE_START);
	}

	@Test
	void negativeConfigurationSnapshotIsRejected() {
		Contract contract = draft();

		assertThatThrownBy(() -> contract.activate(EFFECTIVE_START, -1, new BigDecimal("0.0010")))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("gracePeriodDays");
		assertThat(contract.getStatus()).isEqualTo(ContractStatus.DRAFT);

		assertThatThrownBy(() -> contract.activate(EFFECTIVE_START, 3, null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("penaltyRateDaily");

		assertThatThrownBy(() -> contract.activate(EFFECTIVE_START, 3, new BigDecimal("-0.0001")))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("penaltyRateDaily");
	}

	private static Contract draft() {
		Customer customer = new Customer("Budi Santoso", "3171012501900001", "nik-hash",
				"08123456789", "phone-lookup", "Jakarta");
		Asset asset = new Asset(AssetType.MOTORCYCLE, "Honda", "Beat", null, "B1234XY");
		// Provisional configuration snapshot, as written when the draft is created.
		return new Contract("MF-202601-0001", customer, asset, new BigDecimal("20000000.00"),
				new BigDecimal("16000000.00"), new BigDecimal("4000000.00"), 12, InterestScheme.FLAT,
				new BigDecimal("0.0150"), 3, new BigDecimal("0.0010"), PLANNED_START, "idem-key");
	}
}