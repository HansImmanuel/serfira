package com.serfira.contract.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * E4 — {@link Contract#reopenFromMaturity()}, the transition a payment void uses to undo a MATURITY
 * auto-close when the voided payment was what matured the contract (task T16, ADR-020 D1.3).
 *
 * <p>Only a {@code CLOSED}/{@code MATURITY} contract may reopen; a {@code SETTLEMENT} close is never
 * reopened by a void (ADR-020 D5).
 */
class ContractReopenFromMaturityTest {

	private static final OffsetDateTime CLOSED_AT =
			OffsetDateTime.of(2026, 9, 21, 9, 30, 0, 0, ZoneOffset.ofHours(7));

	@Test
	void reopeningAMaturityClosedContractReturnsItToActiveAndClearsTheClose() {
		Contract contract = active();
		contract.close(ClosedReason.MATURITY, CLOSED_AT);
		assertThat(contract.getStatus()).isEqualTo(ContractStatus.CLOSED);

		boolean reopened = contract.reopenFromMaturity();

		assertThat(reopened).isTrue();
		assertThat(contract.isActive()).isTrue();
		assertThat(contract.getClosedAt()).isNull();
		assertThat(contract.getClosedReason()).isNull();
	}

	@Test
	void reopeningAnAlreadyActiveContractIsANoOp() {
		Contract contract = active();

		boolean reopened = contract.reopenFromMaturity();

		assertThat(reopened).isFalse();
		assertThat(contract.isActive()).isTrue();
	}

	@Test
	void aSettlementCloseCannotBeReopenedByAVoid() {
		Contract contract = active();
		contract.close(ClosedReason.SETTLEMENT, CLOSED_AT);

		assertThatThrownBy(contract::reopenFromMaturity)
				.isInstanceOf(ContractStateException.class)
				.hasMessageContaining("SETTLEMENT");
	}

	private static Contract active() {
		Customer customer = new Customer("Budi Santoso", "3171012501900001", "nik-hash",
				"08123456789", "phone-lookup", "Jakarta");
		Asset asset = new Asset(AssetType.MOTORCYCLE, "Honda", "Beat", null, "B1234XY");
		Contract contract = new Contract("MF-202601-0001", customer, asset, new BigDecimal("20000000.00"),
				new BigDecimal("16000000.00"), new BigDecimal("4000000.00"), 12, InterestScheme.FLAT,
				new BigDecimal("0.0150"), 3, new BigDecimal("0.0010"), LocalDate.of(2026, 1, 31), "idem-key");
		contract.activate(LocalDate.of(2026, 1, 31), 3, new BigDecimal("0.0010"));
		return contract;
	}
}
