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
 * T3 daily servicing behavior against PostgreSQL, Flyway V1–V10, and the real financial ports. The aging
 * step's own behavior (T6) is covered by {@code DailyAgingJobIT}; here it only adds its {@code job_run} row.
 */
@Import({TestcontainersConfiguration.class, DailyServicingJobIT.FixedClockConfig.class})
@SpringBootTest
class DailyServicingJobIT {

	private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 3, 5);
	private static final LocalDate FIRST_CHARGEABLE_DATE = LocalDate.of(2026, 3, 4);

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
	void servicesOnlyActiveContractsInBillingThenPenaltyOrderAndAuditsSystem() {
		UUID overdue = createContract(1, LocalDate.of(2026, 1, 31), true);
		UUID current = createContract(2, LocalDate.of(2026, 3, 1), true);
		UUID draft = createContract(3, LocalDate.of(2026, 1, 31), false);
		UUID closed = createContract(4, LocalDate.of(2026, 1, 31), true);
		UUID terminated = createContract(5, LocalDate.of(2026, 1, 31), true);
		closeForFixture(closed);
		terminateForFixture(terminated);

		job.run(BUSINESS_DATE);

		assertThat(journalCount(overdue, "BILLING")).isEqualTo(1L);
		assertThat(accrualCount(overdue)).isEqualTo(2L);
		assertThat(accrualDates(overdue)).containsExactly(FIRST_CHARGEABLE_DATE, BUSINESS_DATE);
		assertThat(journalCount(overdue, "PENALTY_ACCRUAL")).isEqualTo(2L);
		assertThat(journalCount(current, "BILLING")).isZero();
		assertThat(accrualCount(current)).isZero();
		assertThat(financialJobWriteCount(draft)).isZero();
		assertThat(financialJobWriteCount(closed)).isZero();
		assertThat(financialJobWriteCount(terminated)).isZero();

		// One invocation = one row per step: billing, penalty-accrual and aging (A-9; aging added by T6).
		assertJobRuns(BUSINESS_DATE, 2, 0, "COMPLETED", 3L);
		assertThat(nonSystemFinancialAuditRows()).isZero();
		assertThat(jdbc.queryForObject("select count(*) from job_run where created_by is distinct from ?",
				Long.class, AuditContext.SYSTEM_USER_ID)).isZero();
	}

	@Test
	void rerunningTheSameDateWritesNoNewFinancialRowsButRecordsTheInvocation() {
		UUID overdue = createContract(10, LocalDate.of(2026, 1, 31), true);
		job.run(BUSINESS_DATE);
		long billingBefore = journalCount(overdue, "BILLING");
		long penaltyBefore = journalCount(overdue, "PENALTY_ACCRUAL");
		long accrualBefore = accrualCount(overdue);

		job.run(BUSINESS_DATE);

		assertThat(journalCount(overdue, "BILLING")).isEqualTo(billingBefore);
		assertThat(journalCount(overdue, "PENALTY_ACCRUAL")).isEqualTo(penaltyBefore);
		assertThat(accrualCount(overdue)).isEqualTo(accrualBefore);
		// Two invocations × three step rows (billing, penalty-accrual, aging).
		assertJobRuns(BUSINESS_DATE, 1, 0, "COMPLETED", 6L);
	}

	@Test
	void aLaterBackfillAddsOnlyMissingPenaltyDates() {
		UUID overdue = createContract(20, LocalDate.of(2026, 1, 31), true);
		fixedClock().setDate(BUSINESS_DATE.plusDays(3));

		job.run(BUSINESS_DATE);
		job.run(BUSINESS_DATE.plusDays(3));

		assertThat(accrualDates(overdue)).containsExactly(
				LocalDate.of(2026, 3, 4), LocalDate.of(2026, 3, 5), LocalDate.of(2026, 3, 6),
				LocalDate.of(2026, 3, 7), LocalDate.of(2026, 3, 8));
		assertThat(journalCount(overdue, "BILLING")).isEqualTo(1L);
		assertThat(journalCount(overdue, "PENALTY_ACCRUAL")).isEqualTo(5L);
		assertJobRuns(BUSINESS_DATE, 1, 0, "COMPLETED", 3L);
		assertJobRuns(BUSINESS_DATE.plusDays(3), 1, 0, "COMPLETED", 3L);
	}

	@Test
	void oneContractFailureRollsBackItsWorkAndDoesNotStopOtherContracts() {
		UUID healthy = createContract(30, LocalDate.of(2026, 1, 31), true);
		UUID broken = createContract(31, LocalDate.of(2026, 1, 31), true);
		// Test-only corrupt fixture: billing sees no installment, then the penalty snapshot rejects an ACTIVE
		// contract without a schedule. The real write path cannot create this state.
		jdbc.update("delete from installment where contract_id = ?", broken);

		job.run(BUSINESS_DATE);

		assertThat(journalCount(healthy, "BILLING")).isEqualTo(1L);
		assertThat(accrualCount(healthy)).isEqualTo(2L);
		assertThat(journalCount(broken, "BILLING")).isZero();
		assertThat(accrualCount(broken)).isZero();
		// The corrupt contract also fails the aging step (no schedule to age), so all three rows agree.
		assertJobRuns(BUSINESS_DATE, 1, 1, "FAILED", 3L);
	}

	private UUID createContract(int sequence, LocalDate plannedStartDate, boolean activate) {
		ContractResponse draft = commands.create("daily-job-" + sequence + "-" + UUID.randomUUID(),
				new CreateContractRequest(
						new CreateCustomerRequest("Job Customer " + sequence,
								String.format("31710125019%05d", sequence),
								String.format("08123000%04d", sequence), "Jakarta"),
						new CreateAssetRequest(AssetType.MOTORCYCLE, "Honda", "Beat", null,
								String.format("B%04dJOB", sequence)),
						new BigDecimal("20000000.00"), new BigDecimal("4000000.00"), 12,
						InterestScheme.FLAT, new BigDecimal("0.0150"), plannedStartDate));
		if (activate) {
			commands.activate(draft.id(), null);
		}
		return draft.id();
	}

	private void closeForFixture(UUID contractId) {
		jdbc.update("update contract set status = 'CLOSED', closed_at = clock_timestamp(), "
				+ "closed_reason = 'MATURITY' where id = ?", contractId);
	}

	private void terminateForFixture(UUID contractId) {
		jdbc.update("update contract set status = 'TERMINATED', write_off_reason = 'test fixture' where id = ?",
				contractId);
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

	private List<LocalDate> accrualDates(UUID contractId) {
		return jdbc.queryForList("""
				select pa.accrual_date from penalty_accrual pa
				join installment i on i.id = pa.installment_id
				where i.contract_id = ? order by pa.accrual_date
				""", LocalDate.class, contractId);
	}

	private long financialJobWriteCount(UUID contractId) {
		return journalCount(contractId, "BILLING") + journalCount(contractId, "PENALTY_ACCRUAL")
				+ accrualCount(contractId);
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

	private long nonSystemFinancialAuditRows() {
		return jdbc.queryForObject("""
				select
				  (select count(*) from penalty_accrual where created_by is distinct from ?) +
				  (select count(*) from journal_entry where ref_type in ('BILLING', 'PENALTY_ACCRUAL')
				      and created_by is distinct from ?) +
				  (select count(*) from journal_line jl join journal_entry je on je.id = jl.journal_entry_id
				      where je.ref_type in ('BILLING', 'PENALTY_ACCRUAL') and jl.created_by is distinct from ?)
				""", Long.class, AuditContext.SYSTEM_USER_ID, AuditContext.SYSTEM_USER_ID,
				AuditContext.SYSTEM_USER_ID);
	}

	private FixedClock fixedClock() {
		return (FixedClock) clock;
	}

	private void truncateDomainTables() {
		// Never delete/truncate ShedLock rows at runtime: JdbcTemplateLockProvider caches known lock names.
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
