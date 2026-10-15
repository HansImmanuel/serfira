package com.serfira.contract.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F5 — {@link Contract#terminateByWriteOff(String, UUID, OffsetDateTime)}, the transition a receivable
 * write-off uses to close an ACTIVE contract {@code TERMINATED} (task T19, DM §1.3/§1.12).
 *
 * <p>Only an ACTIVE contract may be written off; it records the reason and the recording actor (never a
 * body value), keeping the row coherent for the V4/V19 {@code ck_contract_terminated_coherence} CHECK
 * (TERMINATED ⇒ reason + recorded_by both NOT NULL).
 */
class ContractTerminateByWriteOffTest {

	private static final OffsetDateTime WRITTEN_OFF_AT =
			OffsetDateTime.of(2026, 9, 21, 9, 30, 0, 0, ZoneOffset.ofHours(7));
	private static final UUID RECORDED_BY = UUID.fromString("3f2c3b8e-7f0a-4c55-9a3c-5b1d2e4f6a7b");

	@Test
	void terminatingAnActiveContractRecordsTheReasonActorAndCloseInstant() {
		Contract contract = active();

		contract.terminateByWriteOff("  collection decision CD-42  ", RECORDED_BY, WRITTEN_OFF_AT);

		assertThat(contract.getStatus()).isEqualTo(ContractStatus.TERMINATED);
		assertThat(contract.getWriteOffReason()).isEqualTo("collection decision CD-42");
		assertThat(contract.getWriteOffRecordedBy()).isEqualTo(RECORDED_BY);
		assertThat(contract.getClosedAt()).isEqualTo(WRITTEN_OFF_AT);
		// closed_reason is reserved for CLOSED, not TERMINATED.
		assertThat(contract.getClosedReason()).isNull();
	}

	@Test
	void aDraftContractCannotBeWrittenOff() {
		Contract contract = draft();

		assertThatThrownBy(() -> contract.terminateByWriteOff("reason", RECORDED_BY, WRITTEN_OFF_AT))
				.isInstanceOf(ContractStateException.class)
				.hasMessageContaining("DRAFT");
	}

	@Test
	void aClosedContractCannotBeWrittenOff() {
		Contract contract = active();
		contract.close(ClosedReason.MATURITY, WRITTEN_OFF_AT);

		assertThatThrownBy(() -> contract.terminateByWriteOff("reason", RECORDED_BY, WRITTEN_OFF_AT))
				.isInstanceOf(ContractStateException.class)
				.hasMessageContaining("CLOSED");
	}

	@Test
	void anAlreadyTerminatedContractCannotBeWrittenOffAgain() {
		Contract contract = active();
		contract.terminateByWriteOff("reason", RECORDED_BY, WRITTEN_OFF_AT);

		assertThatThrownBy(() -> contract.terminateByWriteOff("reason", RECORDED_BY, WRITTEN_OFF_AT))
				.isInstanceOf(ContractStateException.class)
				.hasMessageContaining("TERMINATED");
	}

	@Test
	void aBlankReasonIsRejected() {
		Contract contract = active();

		assertThatThrownBy(() -> contract.terminateByWriteOff("   ", RECORDED_BY, WRITTEN_OFF_AT))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void aNullRecordingActorIsRejected() {
		Contract contract = active();

		assertThatThrownBy(() -> contract.terminateByWriteOff("reason", null, WRITTEN_OFF_AT))
				.isInstanceOf(NullPointerException.class);
	}

	private static Contract active() {
		Contract contract = draft();
		contract.activate(LocalDate.of(2026, 1, 31), 3, new BigDecimal("0.0010"));
		return contract;
	}

	private static Contract draft() {
		Customer customer = new Customer("Budi Santoso", "3171012501900001", "nik-hash",
				"08123456789", "phone-lookup", "Jakarta");
		Asset asset = new Asset(AssetType.MOTORCYCLE, "Honda", "Beat", null, "B1234XY");
		return new Contract("MF-202601-0001", customer, asset, new BigDecimal("20000000.00"),
				new BigDecimal("16000000.00"), new BigDecimal("4000000.00"), 12, InterestScheme.FLAT,
				new BigDecimal("0.0150"), 3, new BigDecimal("0.0010"), LocalDate.of(2026, 1, 31), "idem-key");
	}
}
