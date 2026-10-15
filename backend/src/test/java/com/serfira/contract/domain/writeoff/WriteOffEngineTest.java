package com.serfira.contract.domain.writeoff;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F5 — {@link WriteOffEngine}, the pure per-component cap of a write-off (task T19, DM §1.12). Golden
 * values: the engine keeps the same residual the settlement waterfall uses (effective penalty, then unpaid
 * recognized interest, then unpaid principal), never writes off future unrecognized interest, and skips
 * already-terminal installments.
 */
class WriteOffEngineTest {

	private static final UUID I1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final UUID I2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
	private static final UUID I3 = UUID.fromString("00000000-0000-0000-0000-000000000003");

	@Test
	void capsEffectivePenaltyThenUnpaidInterestThenUnpaidPrincipal() {
		// period 1: nothing paid, penalty 50, recognized interest 100, principal 1000 → full receivable owed
		WriteOffInstallmentInput period1 = new WriteOffInstallmentInput(I1, 1, false,
				new BigDecimal("1000.00"), new BigDecimal("100.00"), new BigDecimal("50.00"),
				new BigDecimal("0.00"));

		List<WriteOffLine> lines = WriteOffEngine.cap(List.of(period1));

		assertThat(lines).hasSize(1);
		WriteOffLine line = lines.get(0);
		assertThat(line.penalty()).isEqualByComparingTo("50.00");
		assertThat(line.interest()).isEqualByComparingTo("100.00");
		assertThat(line.principal()).isEqualByComparingTo("1000.00");
		assertThat(line.total()).isEqualByComparingTo("1150.00");
	}

	@Test
	void aPartPaidInstallmentWritesOffOnlyTheResidualPastPenalty() {
		// resolved 400 (of which 0 was penalty — penalty unpaid), so resolvedBeyondPenalty = 400:
		//   interest residual = max(0, 100 − 400) = 0
		//   principal residual = max(0, 1000 − max(0, 400 − 100)) = max(0, 1000 − 300) = 700
		WriteOffInstallmentInput partPaid = new WriteOffInstallmentInput(I1, 1, false,
				new BigDecimal("1000.00"), new BigDecimal("100.00"), new BigDecimal("50.00"),
				new BigDecimal("400.00"));

		WriteOffLine line = WriteOffEngine.cap(List.of(partPaid)).get(0);

		assertThat(line.penalty()).isEqualByComparingTo("50.00");
		assertThat(line.interest()).isEqualByComparingTo("0.00");
		assertThat(line.principal()).isEqualByComparingTo("700.00");
		assertThat(line.total()).isEqualByComparingTo("750.00");
	}

	@Test
	void futureUnrecognizedInterestIsNeverWrittenOff() {
		// A fully-future period: principal scheduled, but no interest recognized yet and no penalty.
		WriteOffInstallmentInput future = new WriteOffInstallmentInput(I3, 3, false,
				new BigDecimal("1000.00"), new BigDecimal("0.00"), new BigDecimal("0.00"),
				new BigDecimal("0.00"));

		WriteOffLine line = WriteOffEngine.cap(List.of(future)).get(0);

		assertThat(line.interest()).isEqualByComparingTo("0.00");
		assertThat(line.principal()).isEqualByComparingTo("1000.00");
		assertThat(line.total()).isEqualByComparingTo("1000.00");
	}

	@Test
	void terminalInstallmentsAreSkipped() {
		WriteOffInstallmentInput settled = new WriteOffInstallmentInput(I1, 1, true,
				new BigDecimal("1000.00"), new BigDecimal("100.00"), new BigDecimal("0.00"),
				new BigDecimal("1100.00"));
		WriteOffInstallmentInput open = new WriteOffInstallmentInput(I2, 2, false,
				new BigDecimal("1000.00"), new BigDecimal("100.00"), new BigDecimal("0.00"),
				new BigDecimal("0.00"));

		List<WriteOffLine> lines = WriteOffEngine.cap(List.of(settled, open));

		assertThat(lines).extracting(WriteOffLine::periodNo).containsExactly(2);
	}

	@Test
	void linesComeBackOldestPeriodFirst() {
		WriteOffInstallmentInput period2 = new WriteOffInstallmentInput(I2, 2, false,
				new BigDecimal("1000.00"), new BigDecimal("0.00"), new BigDecimal("0.00"),
				new BigDecimal("0.00"));
		WriteOffInstallmentInput period1 = new WriteOffInstallmentInput(I1, 1, false,
				new BigDecimal("1000.00"), new BigDecimal("0.00"), new BigDecimal("0.00"),
				new BigDecimal("0.00"));

		List<WriteOffLine> lines = WriteOffEngine.cap(List.of(period2, period1));

		assertThat(lines).extracting(WriteOffLine::periodNo).containsExactly(1, 2);
	}
}
