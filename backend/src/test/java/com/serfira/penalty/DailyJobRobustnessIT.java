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
import com.serfira.shared.audit.AuditContext;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T26 robustness behavior (CR-07 stale-row recovery / loop-escape finalize, CR-08 keyset batching) against
 * PostgreSQL and Flyway V1–V13. The backoff sequence (CR-09) is unit-tested with a fake Sleeper.
 */
@Import({TestcontainersConfiguration.class, DailyJobRobustnessIT.FixedClockConfig.class})
@SpringBootTest(properties = "serfira.jobs.daily-servicing.batch-size=2")
class DailyJobRobustnessIT {

	private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 3, 5);

	@Autowired
	LockedDailyServicingJob job;

	@Autowired
	ContractCommandService commands;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	Clock clock;

	@BeforeEach
	void resetState() {
		truncateDomainTables();
		fixedClock().setDate(BUSINESS_DATE);
	}

	@AfterEach
	void leaveSharedDatabaseClean() {
		truncateDomainTables();
	}

	@Test
	void theNextLockedRunAbandonsLeftoverRunningRowsAndLeavesNoneRunning() {
		// Simulate a crashed previous run: three RUNNING rows with no finished_at.
		seedRunningRow("billing");
		seedRunningRow("penalty-accrual");
		seedRunningRow("aging");
		createContract(1, LocalDate.of(2026, 1, 31), true);

		job.run(BUSINESS_DATE);

		// No RUNNING row of the daily-servicing names survives (acceptance criterion).
		assertThat(statusCount("RUNNING")).isZero();
		// The three seeded crash remnants are now ABANDONED with finished_at set.
		assertThat(abandonedWithFinishedAtCount()).isEqualTo(3L);
		// This run's own three rows completed normally.
		assertThat(countForDate(BUSINESS_DATE, "COMPLETED")).isEqualTo(3L);
	}

	@Test
	void batchingOverMultiplePagesProcessesEachContractExactlyOnceLikeASingleBatch() {
		UUID a = createContract(10, LocalDate.of(2026, 1, 31), true);
		UUID b = createContract(11, LocalDate.of(2026, 1, 31), true);
		UUID c = createContract(12, LocalDate.of(2026, 1, 31), true);
		UUID d = createContract(13, LocalDate.of(2026, 1, 31), true);
		UUID e = createContract(14, LocalDate.of(2026, 1, 31), true);

		// batch-size is 2 (class-level property), so five ACTIVE contracts span three keyset pages.
		job.run(BUSINESS_DATE);

		for (UUID contractId : List.of(a, b, c, d, e)) {
			// Each contract billed and penalty-accrued exactly once — no dupes, no omissions.
			assertThat(journalCount(contractId, "BILLING")).isEqualTo(1L);
			assertThat(accrualCount(contractId)).isEqualTo(2L);
		}
		// Same three job_run rows and counters as a single batch would produce for five contracts.
		assertJobRuns(BUSINESS_DATE, 5, 0, "COMPLETED", 3L);
	}

	private void seedRunningRow(String jobName) {
		jdbc.update("""
				insert into job_run (id, job_name, business_date, started_at, records_processed,
					records_failed, status, created_at, created_by, updated_at, updated_by)
				values (gen_random_uuid(), ?, ?, clock_timestamp(), 0, 0, 'RUNNING',
					clock_timestamp(), ?, clock_timestamp(), ?)
				""", jobName, BUSINESS_DATE.minusDays(1), AuditContext.SYSTEM_USER_ID,
				AuditContext.SYSTEM_USER_ID);
	}

	private long statusCount(String status) {
		return jdbc.queryForObject("""
				select count(*) from job_run
				where status = ? and job_name in ('billing', 'penalty-accrual', 'aging')
				""", Long.class, status);
	}

	private long abandonedWithFinishedAtCount() {
		return jdbc.queryForObject("""
				select count(*) from job_run
				where status = 'ABANDONED' and finished_at is not null
					and job_name in ('billing', 'penalty-accrual', 'aging')
				""", Long.class);
	}

	private long countForDate(LocalDate businessDate, String status) {
		return jdbc.queryForObject("""
				select count(*) from job_run where business_date = ? and status = ?
				""", Long.class, businessDate, status);
	}

	private UUID createContract(int sequence, LocalDate plannedStartDate, boolean activate) {
		ContractResponse draft = commands.create("robust-" + sequence + "-" + UUID.randomUUID(),
				new CreateContractRequest(
						new CreateCustomerRequest("Robust Customer " + sequence,
								String.format("31710125029%05d", sequence),
								String.format("08124000%04d", sequence), "Jakarta"),
						new CreateAssetRequest(AssetType.MOTORCYCLE, "Honda", "Beat", null,
								String.format("B%04dRBT", sequence)),
						new BigDecimal("20000000.00"), new BigDecimal("4000000.00"), 12,
						InterestScheme.FLAT, new BigDecimal("0.0150"), plannedStartDate));
		if (activate) {
			commands.activate(draft.id(), null);
		}
		return draft.id();
	}

	private long journalCount(UUID contractId, String refType) {
		return jdbc.queryForObject("""
				select count(distinct je.id)
				  from journal_entry je
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

	private void assertJobRuns(LocalDate businessDate, int processed, int failed, String status, long expectedRows) {
		assertThat(jdbc.queryForObject("select count(*) from job_run where business_date = ?", Long.class,
				businessDate)).isEqualTo(expectedRows);
		assertThat(jdbc.queryForObject("""
				select count(*) from job_run
				where business_date = ? and records_processed = ? and records_failed = ? and status = ?
					and finished_at is not null
				""", Long.class, businessDate, processed, failed, status)).isEqualTo(expectedRows);
	}

	private FixedClock fixedClock() {
		return (FixedClock) clock;
	}

	private void truncateDomainTables() {
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

	@TestConfiguration(proxyBeanMethods = false)
	static class FixedClockConfig {
		@Bean
		@Primary
		Clock fixedClock() {
			return new FixedClock(BUSINESS_DATE);
		}
	}
}
