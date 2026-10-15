package com.serfira.penalty;

import com.serfira.penalty.domain.consistency.ConsistencyCheckEngine;
import com.serfira.penalty.domain.consistency.ConsistencyCheckType;
import com.serfira.penalty.domain.consistency.ConsistencyDiscrepancy;
import com.serfira.penalty.domain.consistency.ContractReconciliationInput;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Golden tests for the pure consistency-check engine (F1 / story L-3, Addendum §7.1): no Spring, no
 * database, hardcoded expected values. Each test pins which of the three checks (A PAYMENT_CASH,
 * B ALLOCATION_PAYMENT, C RECEIVABLE_INSTALLMENT) flags drift and the exact expected/actual/diff recorded.
 */
class ConsistencyCheckEngineTest {

	private static ContractReconciliationInput input(String postedPayment, String postedAllocation,
			String ledgerCash, String receivableResidual, String ledgerReceivable) {
		return new ContractReconciliationInput(new BigDecimal(postedPayment), new BigDecimal(postedAllocation),
				new BigDecimal(ledgerCash), new BigDecimal(receivableResidual), new BigDecimal(ledgerReceivable));
	}

	@Test
	void aFullyReconciledContractProducesNoDiscrepancy() {
		// payment == cash, allocation == payment, receivable residual == ledger PIUTANG balance.
		List<ConsistencyDiscrepancy> drift = ConsistencyCheckEngine.check(
				input("1000000.00", "1000000.00", "1000000.00", "4000000.00", "4000000.00"));
		assertThat(drift).isEmpty();
	}

	@Test
	void zeroesOnEveryCheckReconcile() {
		List<ConsistencyDiscrepancy> drift = ConsistencyCheckEngine.check(
				input("0.00", "0.00", "0.00", "0.00", "0.00"));
		assertThat(drift).isEmpty();
	}

	@Test
	void differentScaleRepresentationsOfZeroStillReconcile() {
		// 0 vs 0.00 must be equal (compareTo, not equals): every figure is a NUMERIC(19,2) aggregate.
		List<ConsistencyDiscrepancy> drift = ConsistencyCheckEngine.check(
				new ContractReconciliationInput(BigDecimal.ZERO, new BigDecimal("0.00"), new BigDecimal("0"),
						new BigDecimal("0.0000"), BigDecimal.ZERO));
		assertThat(drift).isEmpty();
	}

	@Test
	void paymentCashDriftIsFlaggedWithSignedDiff() {
		// Payment recorded 1,000,000 but only 900,000 of KAS reached the ledger: check A fails, +100,000.
		List<ConsistencyDiscrepancy> drift = ConsistencyCheckEngine.check(
				input("1000000.00", "1000000.00", "900000.00", "0.00", "0.00"));
		assertThat(drift).hasSize(1);
		ConsistencyDiscrepancy a = drift.get(0);
		assertThat(a.type()).isEqualTo(ConsistencyCheckType.PAYMENT_CASH);
		assertThat(a.expected()).isEqualByComparingTo("1000000.00");
		assertThat(a.actual()).isEqualByComparingTo("900000.00");
		assertThat(a.diff()).isEqualByComparingTo("100000.00");
	}

	@Test
	void allocationDriftIsFlaggedWhenAllocationsDoNotSumToThePayment() {
		// Σ allocation 950,000 ≠ payment 1,000,000 (invariant 6 broken): check B fails, +50,000. KAS matches
		// the payment, so check A still balances.
		List<ConsistencyDiscrepancy> drift = ConsistencyCheckEngine.check(
				input("1000000.00", "950000.00", "1000000.00", "0.00", "0.00"));
		assertThat(drift).hasSize(1);
		ConsistencyDiscrepancy b = drift.get(0);
		assertThat(b.type()).isEqualTo(ConsistencyCheckType.ALLOCATION_PAYMENT);
		assertThat(b.expected()).isEqualByComparingTo("1000000.00");
		assertThat(b.actual()).isEqualByComparingTo("950000.00");
		assertThat(b.diff()).isEqualByComparingTo("50000.00");
	}

	@Test
	void receivableDriftIsFlaggedWithNegativeDiffWhenLedgerExceedsSchedule() {
		// Schedule says 4,000,000 still owed; ledger PIUTANG shows 4,250,000: check C fails, −250,000.
		List<ConsistencyDiscrepancy> drift = ConsistencyCheckEngine.check(
				input("0.00", "0.00", "0.00", "4000000.00", "4250000.00"));
		assertThat(drift).hasSize(1);
		ConsistencyDiscrepancy c = drift.get(0);
		assertThat(c.type()).isEqualTo(ConsistencyCheckType.RECEIVABLE_INSTALLMENT);
		assertThat(c.expected()).isEqualByComparingTo("4000000.00");
		assertThat(c.actual()).isEqualByComparingTo("4250000.00");
		assertThat(c.diff()).isEqualByComparingTo("-250000.00");
	}

	@Test
	void aSubRupiahDriftIsNotToleratedAwayByAnyEpsilon() {
		// One cent of drift is still drift — there is deliberately no tolerance.
		List<ConsistencyDiscrepancy> drift = ConsistencyCheckEngine.check(
				input("1000000.00", "1000000.00", "999999.99", "0.00", "0.00"));
		assertThat(drift).hasSize(1);
		assertThat(drift.get(0).type()).isEqualTo(ConsistencyCheckType.PAYMENT_CASH);
		assertThat(drift.get(0).diff()).isEqualByComparingTo("0.01");
	}

	@Test
	void allThreeChecksCanFailAtOnceAndAreReportedInCheckOrder() {
		List<ConsistencyDiscrepancy> drift = ConsistencyCheckEngine.check(
				input("1000000.00", "950000.00", "900000.00", "4000000.00", "4250000.00"));
		assertThat(drift).extracting(ConsistencyDiscrepancy::type).containsExactly(
				ConsistencyCheckType.PAYMENT_CASH,
				ConsistencyCheckType.ALLOCATION_PAYMENT,
				ConsistencyCheckType.RECEIVABLE_INSTALLMENT);
	}
}
