package com.serfira.penalty;

import com.serfira.TestcontainersConfiguration;
import com.serfira.contract.api.ContractResponse;
import com.serfira.contract.api.CreateAssetRequest;
import com.serfira.contract.api.CreateContractRequest;
import com.serfira.contract.api.CreateCustomerRequest;
import com.serfira.contract.application.ContractCommandService;
import com.serfira.contract.domain.AssetType;
import com.serfira.contract.domain.InterestScheme;
import com.serfira.penalty.application.LockedDailyServicingJob;
import com.serfira.penalty.application.PenaltyAccrualPort;
import com.serfira.penalty.application.PenaltyAccrualService;
import com.serfira.shared.clock.Clock;
import com.serfira.shared.clock.FixedClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Database proof that every retry is a fresh transaction containing both billing and penalty. */
@Import({TestcontainersConfiguration.class, DailyServicingTransactionIT.TransactionTestConfig.class})
@SpringBootTest
class DailyServicingTransactionIT {

	private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 3, 5);

	@Autowired
	LockedDailyServicingJob job;

	@Autowired
	ContractCommandService commands;

	@Autowired
	ControllablePenaltyPort penalty;

	@Autowired
	JdbcTemplate jdbc;

	@BeforeEach
	void resetState() {
		truncateDomainTables();
		penalty.reset();
	}

	@AfterEach
	void leaveSharedDatabaseClean() {
		penalty.reset();
		truncateDomainTables();
	}

	@Test
	void retryableFailuresRollBackBillingAndEventuallyCommitExactlyOnce() {
		UUID contractId = createActiveContract(1);
		penalty.failNextAttempts(contractId, 2);

		job.run(BUSINESS_DATE);

		assertThat(penalty.attempts(contractId)).isEqualTo(3);
		assertThat(journalCount(contractId, "BILLING")).isEqualTo(1L);
		assertThat(journalCount(contractId, "PENALTY_ACCRUAL")).isEqualTo(2L);
		assertThat(accrualCount(contractId)).isEqualTo(2L);
		assertRunCounters(1, 0, "COMPLETED");
	}

	@Test
	void exhaustedConflictRollsBackEveryAttemptAndOtherContractsStillCommit() {
		UUID failing = createActiveContract(10);
		UUID healthy = createActiveContract(11);
		penalty.failNextAttempts(failing, 5);

		job.run(BUSINESS_DATE);

		assertThat(penalty.attempts(failing)).isEqualTo(5);
		assertThat(journalCount(failing, "BILLING")).isZero();
		assertThat(journalCount(failing, "PENALTY_ACCRUAL")).isZero();
		assertThat(accrualCount(failing)).isZero();
		assertThat(journalCount(healthy, "BILLING")).isEqualTo(1L);
		assertThat(journalCount(healthy, "PENALTY_ACCRUAL")).isEqualTo(2L);
		assertThat(accrualCount(healthy)).isEqualTo(2L);
		assertRunCounters(1, 1, "FAILED");
	}

	private UUID createActiveContract(int sequence) {
		ContractResponse draft = commands.create("daily-transaction-" + sequence + "-" + UUID.randomUUID(),
				new CreateContractRequest(
						new CreateCustomerRequest("Transaction Customer " + sequence,
								String.format("31710125018%05d", sequence),
								String.format("08124000%04d", sequence), "Jakarta"),
						new CreateAssetRequest(AssetType.MOTORCYCLE, "Honda", "Beat", null,
								String.format("B%04dTX", sequence)),
						new BigDecimal("20000000.00"), new BigDecimal("4000000.00"), 12,
						InterestScheme.FLAT, new BigDecimal("0.0150"), LocalDate.of(2026, 1, 31)));
		commands.activate(draft.id(), null);
		return draft.id();
	}

	private long journalCount(UUID contractId, String refType) {
		return jdbc.queryForObject("""
				select count(distinct je.id) from journal_entry je
				join journal_line jl on jl.journal_entry_id = je.id
				where jl.contract_id = ? and je.ref_type = ?
				""", Long.class, contractId, refType);
	}

	private long accrualCount(UUID contractId) {
		return jdbc.queryForObject("""
				select count(*) from penalty_accrual pa
				join installment i on i.id = pa.installment_id
				where i.contract_id = ?
				""", Long.class, contractId);
	}

	private void assertRunCounters(int processed, int failed, String status) {
		assertThat(jdbc.queryForObject("""
				select count(*) from job_run
				where business_date = ? and records_processed = ? and records_failed = ? and status = ?
				""", Long.class, BUSINESS_DATE, processed, failed, status)).isEqualTo(2L);
	}

	private void truncateDomainTables() {
		// ShedLock owns and caches its row. Removing it while the provider is alive makes later acquisitions skip.
		jdbc.execute("""
				TRUNCATE TABLE
					document_number_counter, refresh_token, idempotency_keys, job_run,
					reconciliation_exception, outbox_events,
					settlement_credit_application, settlement_allocation, penalty_adjustment, penalty_accrual,
					contract_credit_application, contract_credit, payment_allocation, payment,
					settlement_quote, settlement, installment, journal_line, journal_entry,
					contract, asset, customer
				CASCADE""");
	}

	static class ControllablePenaltyPort implements PenaltyAccrualPort {

		private final PenaltyAccrualService delegate;
		private final Map<UUID, Integer> failuresRemaining = new HashMap<>();
		private final Map<UUID, Integer> attempts = new HashMap<>();

		ControllablePenaltyPort(PenaltyAccrualService delegate) {
			this.delegate = delegate;
		}

		@Override
		public int accrueDuePenalty(UUID contractId, LocalDate businessDate) {
			attempts.merge(contractId, 1, Integer::sum);
			int remaining = failuresRemaining.getOrDefault(contractId, 0);
			if (remaining > 0) {
				failuresRemaining.put(contractId, remaining - 1);
				throw new ObjectOptimisticLockingFailureException(Object.class, contractId);
			}
			return delegate.accrueDuePenalty(contractId, businessDate);
		}

		void failNextAttempts(UUID contractId, int count) {
			failuresRemaining.put(contractId, count);
		}

		int attempts(UUID contractId) {
			return attempts.getOrDefault(contractId, 0);
		}

		void reset() {
			failuresRemaining.clear();
			attempts.clear();
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class TransactionTestConfig {
		@Bean
		@Primary
		Clock fixedClock() {
			return new FixedClock(BUSINESS_DATE);
		}

		@Bean
		@Primary
		ControllablePenaltyPort controllablePenaltyPort(PenaltyAccrualService delegate) {
			return new ControllablePenaltyPort(delegate);
		}
	}
}
