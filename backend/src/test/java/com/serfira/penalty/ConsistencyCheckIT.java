package com.serfira.penalty;

import com.serfira.TestcontainersConfiguration;
import com.serfira.contract.api.ContractResponse;
import com.serfira.contract.api.CreateAssetRequest;
import com.serfira.contract.api.CreateContractRequest;
import com.serfira.contract.api.CreateCustomerRequest;
import com.serfira.contract.application.ContractCommandService;
import com.serfira.contract.domain.AssetType;
import com.serfira.contract.domain.InterestScheme;
import com.serfira.payment.api.PaymentRequest;
import com.serfira.payment.application.PaymentApplicationService;
import com.serfira.payment.domain.PaymentChannel;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T17 consistency-check behavior (F1 / story L-3, Addendum §7.1/§7.3) against PostgreSQL and Flyway V1–V19.
 * The check runs as the daily job's fourth step; this suite drives it through {@link LockedDailyServicingJob}
 * (the same entry point the other daily-job ITs use) and asserts the {@code consistency-check} {@code job_run}
 * row and the {@code reconciliation_exception} rows it records. The three pure predicates are golden-tested
 * without Spring in {@link ConsistencyCheckEngineTest}.
 */
@Import({TestcontainersConfiguration.class, ConsistencyCheckIT.FixedClockConfig.class})
@SpringBootTest
class ConsistencyCheckIT {

	private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 3, 5);
	private static final String CONSISTENCY = "consistency-check";

	@Autowired
	LockedDailyServicingJob job;

	@Autowired
	ContractCommandService commands;

	@Autowired
	PaymentApplicationService payments;

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
	void aCleanPortfolioRecordsAConsistencyRunWithNoFailuresAndNoExceptions() {
		UUID contractId = createActiveContract(1, LocalDate.of(2026, 1, 31));
		// A real partial payment through the full path: KAS, allocations and the receivable all move
		// together, so every check (A payment=cash, B allocation=payment, C receivable=ledger) balances.
		payments.create(UUID.randomUUID().toString(),
				new PaymentRequest(contractId, new BigDecimal("500000.00"), PaymentChannel.CASH));

		job.run(BUSINESS_DATE);

		assertConsistencyRun(1, 0, "COMPLETED");
		assertThat(reconciliationExceptionCount()).isZero();
	}

	@Test
	void aContractWithNoActivityStillReconcilesCleanly() {
		createActiveContract(2, LocalDate.of(2026, 3, 1));

		job.run(BUSINESS_DATE);

		assertConsistencyRun(1, 0, "COMPLETED");
		assertThat(reconciliationExceptionCount()).isZero();
	}

	@Test
	void aPaymentWithoutCashOrAllocationsIsFlaggedByChecksAandBWithoutMutation() {
		UUID contractId = createActiveContract(3, LocalDate.of(2026, 1, 31));
		// Corrupted fixture: a POSTED payment recorded with no cash journal and no allocations — the real
		// write path cannot produce this (it posts a KAS journal and allocations atomically). It drifts
		// check A (payment 750,000 vs KAS 0) and check B (payment 750,000 vs allocation 0).
		insertBarePayment(contractId, new BigDecimal("750000.00"));

		job.run(BUSINESS_DATE);

		// The contract is still checked (processed) but failed a reconciliation, so the row is FAILED.
		assertConsistencyRun(1, 1, "FAILED");
		assertThat(reconciliationExceptionCount()).isEqualTo(2L);
		assertException(contractId, "PAYMENT_CASH", "750000.00", "0.00", "750000.00");
		assertException(contractId, "ALLOCATION_PAYMENT", "750000.00", "0.00", "750000.00");
		// "Report, do not fix": the stray payment row is untouched, no journal/allocation created.
		assertThat(postedPaymentCount(contractId)).isEqualTo(1L);
		assertThat(allocationCount(contractId)).isZero();
		assertThat(paymentJournalCount(contractId)).isZero();
	}

	@Test
	void aStrayReceivableLedgerEntryIsFlaggedByCheckCWithASignedDiff() {
		UUID contractId = createActiveContract(4, LocalDate.of(2026, 1, 31));
		// Corrupted fixture: a balanced but spurious extra PIUTANG_POKOK entry raises the ledger receivable
		// above what the installment schedule says is owed — check C drift. It is balanced (Dr PIUTANG_POKOK
		// / Cr PENDAPATAN_BUNGA), so it passes the V3/V12 balance triggers; it is "drift" only relative to
		// the installments, which is exactly what check C exists to catch.
		insertStrayReceivableEntry(contractId, new BigDecimal("250000.00"));

		job.run(BUSINESS_DATE);

		assertConsistencyRun(1, 1, "FAILED");
		assertThat(reconciliationExceptionCount()).isEqualTo(1L);
		// expected (schedule residual) is 250,000 LESS than the inflated ledger balance, so diff is negative.
		BigDecimal scheduleResidual = receivableResidualFromSchedule(contractId);
		BigDecimal ledgerReceivable = scheduleResidual.add(new BigDecimal("250000.00"));
		assertException(contractId, "RECEIVABLE_INSTALLMENT",
				scheduleResidual.toPlainString(), ledgerReceivable.toPlainString(), "-250000.00");
	}

	@Test
	void driftOnOneContractDoesNotStopOrFlagACleanContract() {
		UUID clean = createActiveContract(5, LocalDate.of(2026, 3, 1));
		UUID drifted = createActiveContract(6, LocalDate.of(2026, 1, 31));
		insertBarePayment(drifted, new BigDecimal("100000.00"));

		job.run(BUSINESS_DATE);

		// Two contracts checked; one drifted. One job_run row, processed=2, failed=1 → FAILED (A-9).
		assertConsistencyRun(2, 1, "FAILED");
		assertThat(reconciliationExceptionCountFor(clean)).isZero();
		assertThat(reconciliationExceptionCountFor(drifted)).isEqualTo(2L);
	}

	@Test
	void rerunningTheSameDateDoesNotDuplicateAnOpenException() {
		UUID contractId = createActiveContract(8, LocalDate.of(2026, 1, 31));
		// Drifts checks A and B, so the first run records exactly two OPEN rows.
		insertBarePayment(contractId, new BigDecimal("750000.00"));

		job.run(BUSINESS_DATE);
		assertThat(reconciliationExceptionCount()).isEqualTo(2L);

		// A same-date re-run of a still-drifting contract (a supported workflow) must not add a second OPEN
		// row for the same (contract, date, check): recording is idempotent per that key (PR #12 review).
		job.run(BUSINESS_DATE);

		assertThat(reconciliationExceptionCount()).isEqualTo(2L);
		assertException(contractId, "PAYMENT_CASH", "750000.00", "0.00", "750000.00");
		assertException(contractId, "ALLOCATION_PAYMENT", "750000.00", "0.00", "750000.00");
	}

	@Test
	void everyConsistencyRowIsWrittenAsSystem() {
		createActiveContract(7, LocalDate.of(2026, 1, 31));

		job.run(BUSINESS_DATE);

		assertThat(jdbc.queryForObject(
				"select count(*) from job_run where job_name = ? and created_by is distinct from ?",
				Long.class, CONSISTENCY, AuditContext.SYSTEM_USER_ID)).isZero();
	}

	private UUID createActiveContract(int sequence, LocalDate plannedStartDate) {
		ContractResponse draft = commands.create("consistency-" + sequence + "-" + UUID.randomUUID(),
				new CreateContractRequest(
						new CreateCustomerRequest("Consistency Customer " + sequence,
								String.format("31710125039%05d", sequence),
								String.format("08125000%04d", sequence), "Jakarta"),
						new CreateAssetRequest(AssetType.MOTORCYCLE, "Honda", "Beat", null,
								String.format("B%04dCON", sequence)),
						new BigDecimal("20000000.00"), new BigDecimal("4000000.00"), 12,
						InterestScheme.FLAT, new BigDecimal("0.0150"), plannedStartDate));
		commands.activate(draft.id(), null);
		return draft.id();
	}

	private void insertBarePayment(UUID contractId, BigDecimal amount) {
		jdbc.update("""
				insert into payment (id, payment_no, contract_id, amount, channel, paid_at, status,
					idempotency_key, version, created_at, created_by, updated_at, updated_by)
				values (gen_random_uuid(), ?, ?, ?, 'CASH', clock_timestamp(), 'POSTED', ?, 0,
					clock_timestamp(), ?, clock_timestamp(), ?)
				""", "PAY-TEST-" + UUID.randomUUID().toString().substring(0, 8), contractId, amount,
				"stray-" + UUID.randomUUID(), AuditContext.SYSTEM_USER_ID, AuditContext.SYSTEM_USER_ID);
	}

	private void insertStrayReceivableEntry(UUID contractId, BigDecimal amount) {
		UUID entryId = UUID.randomUUID();
		jdbc.update("""
				insert into journal_entry (id, entry_date, ref_type, ref_id, description, posted_at,
					created_at, created_by, updated_at, updated_by)
				values (?, clock_timestamp(), 'BILLING', gen_random_uuid(), 'stray test entry', clock_timestamp(),
					clock_timestamp(), ?, clock_timestamp(), ?)
				""", entryId, AuditContext.SYSTEM_USER_ID, AuditContext.SYSTEM_USER_ID);
		insertStrayLine(entryId, "PIUTANG_POKOK", amount, BigDecimal.ZERO, contractId);
		insertStrayLine(entryId, "PENDAPATAN_BUNGA", BigDecimal.ZERO, amount, contractId);
	}

	private void insertStrayLine(UUID entryId, String account, BigDecimal debit, BigDecimal credit,
			UUID contractId) {
		jdbc.update("""
				insert into journal_line (id, journal_entry_id, entry_date, account_code, debit, credit,
					contract_id, created_at, created_by, updated_at, updated_by)
				values (gen_random_uuid(), ?, clock_timestamp(), ?, ?, ?, ?,
					clock_timestamp(), ?, clock_timestamp(), ?)
				""", entryId, account, debit, credit, contractId,
				AuditContext.SYSTEM_USER_ID, AuditContext.SYSTEM_USER_ID);
	}

	private BigDecimal receivableResidualFromSchedule(UUID contractId) {
		return jdbc.queryForObject("""
				select coalesce(sum(
					principal_amount + recognized_interest_amount + penalty_amount
					- paid_amount - settled_amount - written_off_amount), 0)
				from installment where contract_id = ?
				""", BigDecimal.class, contractId);
	}

	private void assertConsistencyRun(int processed, int failed, String status) {
		assertThat(jdbc.queryForObject("""
				select count(*) from job_run
				where business_date = ? and job_name = ? and records_processed = ? and records_failed = ?
					and status = ? and finished_at is not null
				""", Long.class, BUSINESS_DATE, CONSISTENCY, processed, failed, status)).isEqualTo(1L);
	}

	private void assertException(UUID contractId, String checkType, String expected, String actual, String diff) {
		assertThat(jdbc.queryForObject("""
				select count(*) from reconciliation_exception
				where contract_id = ? and check_type = ? and check_date = ? and status = 'OPEN'
					and expected_amount = ? and actual_amount = ? and diff_amount = ?
				""", Long.class, contractId, checkType, BUSINESS_DATE,
				new BigDecimal(expected), new BigDecimal(actual), new BigDecimal(diff))).isEqualTo(1L);
	}

	private long reconciliationExceptionCount() {
		return jdbc.queryForObject("select count(*) from reconciliation_exception", Long.class);
	}

	private long reconciliationExceptionCountFor(UUID contractId) {
		return jdbc.queryForObject("select count(*) from reconciliation_exception where contract_id = ?",
				Long.class, contractId);
	}

	private long postedPaymentCount(UUID contractId) {
		return jdbc.queryForObject("select count(*) from payment where contract_id = ? and status = 'POSTED'",
				Long.class, contractId);
	}

	private long allocationCount(UUID contractId) {
		return jdbc.queryForObject("""
				select count(*) from payment_allocation pa
				join payment p on p.id = pa.payment_id
				where p.contract_id = ?
				""", Long.class, contractId);
	}

	private long paymentJournalCount(UUID contractId) {
		return jdbc.queryForObject("""
				select count(distinct je.id)
				from journal_entry je
				join journal_line jl on jl.journal_entry_id = je.id
				where jl.contract_id = ? and je.ref_type = 'PAYMENT'
				""", Long.class, contractId);
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
