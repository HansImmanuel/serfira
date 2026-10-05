package com.serfira;

import com.serfira.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Sprint 2 readiness suite for the V3–V5 migration set, run against a real PostgreSQL 16
 * (Testcontainers, fresh per build):
 * <ul>
 *   <li>V3 — commit-time accounting invariants: Σ debit = Σ credit per {@code journal_entry},
 *       Σ allocations = payment.amount, per-installment component caps, and the deferred
 *       replacement of the immediate {@code ck_installment_amounts} CHECK (H-4),</li>
 *   <li>V4 — contract/installment/payment status↔timestamp coherence CHECKs,</li>
 *   <li>V5 — settlement full immutability and settlement_quote snapshot immutability.</li>
 * </ul>
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AccountingInvariantsIT {

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	TransactionTemplate tx;

	private UUID contractId;
	private UUID installmentId;

	@BeforeEach
	void resetDomainState() {
		truncateAll();
		contractId = insertContract();
		installmentId = insertInstallment(contractId, 1);
	}

	@AfterEach
	void leaveCleanSharedDatabase() {
		// The IT suites share one Testcontainers database with no guaranteed execution
		// order; this suite's fixture hashes (e.g. repeat('a', 64)) would otherwise leak
		// into BaselineSchemaIT, whose CTE fixtures assume a clean database.
		truncateAll();
	}

	private void truncateAll() {
		// Shared-DB hygiene identical to ContractPersistenceIT's pattern. TRUNCATE does not fire
		// row-level/deferred triggers, so it stays compatible with the V3 constraint triggers.
		jdbc.execute("""
				TRUNCATE TABLE
					refresh_token, idempotency_keys, job_run, reconciliation_exception, outbox_events,
					settlement_credit_application, settlement_allocation, penalty_adjustment, penalty_accrual,
					contract_credit_application, contract_credit, payment_allocation, payment,
					settlement_quote, settlement, installment, journal_line, journal_entry,
					contract, asset, customer
					CASCADE""");
	}

	// -------------------------------------------------------------------------------------
	// Fixtures
	// -------------------------------------------------------------------------------------

	private UUID insertContract() {
		UUID customerId = jdbc.queryForObject("""
				insert into customer (full_name, nik, nik_hash, phone, phone_lookup, created_at, updated_at)
					values ('IT Customer', 'it-nik-cipher-a', repeat('a', 64), 'it-phone-cipher-a', repeat('b', 64),
						clock_timestamp(), clock_timestamp())
					returning id""", UUID.class);
		UUID assetId = jdbc.queryForObject("""
				insert into asset (asset_type, brand, model, created_at, updated_at)
					values ('MOTORCYCLE', 'Honda', 'Beat', clock_timestamp(), clock_timestamp())
					returning id""", UUID.class);
		return jdbc.queryForObject("""
				insert into contract (contract_no, customer_id, asset_id, asset_price, principal, down_payment,
					tenor_months, interest_scheme, interest_rate, grace_period_days, penalty_rate_daily,
					created_at, updated_at)
					values ('MF-IT-0001', ?, ?, 1000000.00, 900000.00, 100000.00, 12, 'FLAT', 0.0150, 3, 0.0010,
						clock_timestamp(), clock_timestamp())
					returning id""", UUID.class, customerId, assetId);
	}

	private UUID insertInstallment(UUID contractId, int periodNo) {
		return jdbc.queryForObject("""
				insert into installment (contract_id, period_no, due_date, principal_amount, interest_amount,
					created_at, updated_at)
					values (?, ?, date '2026-02-28', 75000.00, 11250.00, clock_timestamp(), clock_timestamp())
					returning id""", UUID.class, contractId, periodNo);
	}

	private UUID insertPayment(BigDecimal amount) {
		return jdbc.queryForObject("""
				insert into payment (payment_no, contract_id, amount, channel, paid_at, idempotency_key,
					created_at, updated_at)
					values (?, ?, ?, 'CASH', clock_timestamp(), ?, clock_timestamp(), clock_timestamp())
					returning id""", UUID.class,
				// payment_no is VARCHAR(30): prefix + 22 hex chars from the random UUID
				"PAY-IT-" + UUID.randomUUID().toString().replace("-", "").substring(0, 22), contractId, amount,
				"key-" + UUID.randomUUID());
	}

	private void insertPaymentWithId(UUID paymentId, BigDecimal amount) {
		jdbc.update("""
				insert into payment (id, payment_no, contract_id, amount, channel, paid_at, idempotency_key,
					created_at, updated_at)
					values (?, ?, ?, ?, 'CASH', clock_timestamp(), ?, clock_timestamp(), clock_timestamp())""",
				paymentId,
				"PAY-IT-" + UUID.randomUUID().toString().replace("-", "").substring(0, 22), contractId, amount,
				"key-" + UUID.randomUUID());
	}

	/**
	 * Insert a payment with a single matching PRINCIPAL allocation in one transaction, so the V12
	 * parent-side check (Σ allocations = amount) and the V3 component caps both pass. The fixture
	 * installment's principal_amount is 75,000, so any {@code amount} up to that is a valid allocation.
	 */
	private UUID insertValidPayment(BigDecimal amount) {
		UUID paymentId = UUID.randomUUID();
		tx.executeWithoutResult(status -> {
			insertPaymentWithId(paymentId, amount);
			insertAllocation(paymentId, "PRINCIPAL", amount.toPlainString());
		});
		return paymentId;
	}

	private void insertAllocation(UUID paymentId, String allocationType, String amount) {
		jdbc.update("""
				insert into payment_allocation (payment_id, installment_id, allocation_type, amount, created_at, updated_at)
					values (?, ?, ?, ?, clock_timestamp(), clock_timestamp())""", paymentId, installmentId, allocationType,
				new BigDecimal(amount));
	}

	private UUID insertQuote() {
		return jdbc.queryForObject("""
				insert into settlement_quote (quote_no, contract_id, quoted_at, valid_until, contract_version,
					outstanding_principal, unpaid_billed_interest, accrued_interest, penalty_outstanding,
					rebate_amount, admin_fee, available_credit, gross_amount, cash_due, created_at, updated_at)
					values ('Q-IT-0001', ?, clock_timestamp(), clock_timestamp() + interval '15 minutes', 0,
						0.00, 0.00, 0.00, 0.00, 0.00, 0.00, 0.00, 0.00, 0.00,
						clock_timestamp(), clock_timestamp())
					returning id""", UUID.class, contractId);
	}

	private long countOf(String table, String column, UUID value) {
		return jdbc.queryForObject("select count(*) from " + table + " where " + column + " = ?", Long.class, value);
	}

	// -------------------------------------------------------------------------------------
	// V3 — migrations applied & Σ debit = Σ credit per journal_entry
	// -------------------------------------------------------------------------------------

	@Test
	void readinessMigrationsV3ThroughV5AreAppliedOnCleanDatabase() {
		List<String> versions = jdbc.queryForList(
				"select version from flyway_schema_history where success order by installed_rank", String.class);
		// B5 added V6/V7 (planned_start_date, contract create safety); later migrations
		// keep arriving, so assert V1–V5 applied in order instead of an exact list.
		assertThat(versions).containsSubsequence("1", "2", "3", "4", "5");
	}

	@Test
	void balancedJournalEntryCommits() {
		UUID entryId = UUID.randomUUID();
		tx.executeWithoutResult(status -> {
			jdbc.update("""
					insert into journal_entry (id, entry_date, ref_type, ref_id, posted_at, created_at)
						values (?, clock_timestamp(), 'TEST', ?, clock_timestamp(), clock_timestamp())""", entryId, entryId);
			jdbc.update("""
					insert into journal_line (journal_entry_id, entry_date, account_code, debit, credit, created_at)
						values (?, clock_timestamp(), 'KAS', 500.00, 0, clock_timestamp())""", entryId);
			jdbc.update("""
					insert into journal_line (journal_entry_id, entry_date, account_code, debit, credit, created_at)
						values (?, clock_timestamp(), 'PIUTANG_POKOK', 0, 500.00, clock_timestamp())""", entryId);
		});
		assertThat(countOf("journal_line", "journal_entry_id", entryId)).isEqualTo(2L);
	}

	@Test
	void unbalancedJournalEntryIsRejectedAtCommitAndNothingPersists() {
		UUID entryId = UUID.randomUUID();
		assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
			jdbc.update("""
					insert into journal_entry (id, entry_date, ref_type, ref_id, posted_at, created_at)
					values (?, clock_timestamp(), 'TEST', ?, clock_timestamp(), clock_timestamp())""", entryId, entryId);
			jdbc.update("""
					insert into journal_line (journal_entry_id, entry_date, account_code, debit, credit, created_at)
					values (?, clock_timestamp(), 'KAS', 500.00, 0, clock_timestamp())""", entryId);
			jdbc.update("""
					insert into journal_line (journal_entry_id, entry_date, account_code, debit, credit, created_at)
					values (?, clock_timestamp(), 'PIUTANG_POKOK', 0, 499.00, clock_timestamp())""", entryId);
		})).isInstanceOf(RuntimeException.class);
		assertThat(countOf("journal_line", "journal_entry_id", entryId)).isZero();
	}

	// -------------------------------------------------------------------------------------
	// V3 — Σ allocations = payment.amount & per-installment component caps
	// -------------------------------------------------------------------------------------

	@Test
	void paymentAllocationsMustTotalPaymentAmountAtCommit() {
		// V12 adds a parent-side deferred check on payment INSERT, so a payment and its allocations
		// must commit in one transaction (as the real write path does). The id is captured inside.
		UUID[] holder = new UUID[1];
		tx.executeWithoutResult(status -> {
			holder[0] = insertPayment(new BigDecimal("1000.00"));
			insertAllocation(holder[0], "PRINCIPAL", "600.00");
			insertAllocation(holder[0], "PRINCIPAL", "400.00");
		});
		assertThat(countOf("payment_allocation", "payment_id", holder[0])).isEqualTo(2L);
	}

	@Test
	void mismatchedPaymentAllocationSumIsRejectedAtCommit() {
		UUID paymentId = UUID.randomUUID();
		assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
			insertPaymentWithId(paymentId, new BigDecimal("1000.00"));
			insertAllocation(paymentId, "PRINCIPAL", "600.00");
			insertAllocation(paymentId, "PRINCIPAL", "300.00");
		})).isInstanceOf(RuntimeException.class);
		assertThat(countOf("payment", "id", paymentId)).isZero();
		assertThat(countOf("payment_allocation", "payment_id", paymentId)).isZero();
	}

	@Test
	void interestAllocationBeyondRecognizedAmountIsRejectedAtCommit() {
		// Fixture recognized_interest_amount = 0 — an INTEREST allocation is impossible by itself.
		UUID paymentId = UUID.randomUUID();
		assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
			insertPaymentWithId(paymentId, new BigDecimal("100.00"));
			insertAllocation(paymentId, "INTEREST", "100.00");
		})).isInstanceOf(RuntimeException.class);
		assertThat(countOf("payment_allocation", "payment_id", paymentId)).isZero();
	}

	@Test
	void recognitionThenAllocationInSameTransactionCommits() {
		// H-4: recognized_interest update and the allocation are separate statements in one tx.
		UUID[] holder = new UUID[1];
		tx.executeWithoutResult(status -> {
			holder[0] = insertPayment(new BigDecimal("100.00"));
			jdbc.update("update installment set recognized_interest_amount = 100.00 where id = ?", installmentId);
			insertAllocation(holder[0], "INTEREST", "100.00");
		});
		assertThat(countOf("payment_allocation", "payment_id", holder[0])).isEqualTo(1L);
	}

	@Test
	void allocationThenRecognitionInSameTransactionCommits() {
		// H-4 mirrored: allocation first, recognition second — deferred checks must still pass.
		UUID[] holder = new UUID[1];
		tx.executeWithoutResult(status -> {
			holder[0] = insertPayment(new BigDecimal("100.00"));
			insertAllocation(holder[0], "INTEREST", "100.00");
			jdbc.update("update installment set recognized_interest_amount = 100.00 where id = ?", installmentId);
		});
		assertThat(countOf("payment_allocation", "payment_id", holder[0])).isEqualTo(1L);
	}

	@Test
	void penaltyAllocationBeyondEffectiveOutstandingIsRejectedAtCommit() {
		// Fixture penalty_amount = 0: any PENALTY allocation must be rejected at commit.
		UUID paymentId = UUID.randomUUID();
		assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
			insertPaymentWithId(paymentId, new BigDecimal("50.00"));
			insertAllocation(paymentId, "PENALTY", "50.00");
		})).isInstanceOf(RuntimeException.class);
		assertThat(countOf("payment_allocation", "payment_id", paymentId)).isZero();
	}

	// -------------------------------------------------------------------------------------
	// V3 — installment resolved-amount guard is commit-time, not per-statement
	// -------------------------------------------------------------------------------------

	@Test
	void resolvedAmountBeyondRecognizedTotalIsRejectedOnlyAtCommit() {
		// recognized_total = 75,000 principal + 10,000 recognized interest = 85,000;
		// paid_amount 90,000 is safe mid-transaction but must fail when the tx commits.
		jdbc.update("update installment set recognized_interest_amount = 10000.00 where id = ?", installmentId);
		assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
			jdbc.update("update installment set recognized_interest_amount = 10000.00 where id = ?", installmentId);
			jdbc.update("update installment set paid_amount = 90000.00 where id = ?", installmentId);
		})).isInstanceOf(RuntimeException.class);
		assertThat(jdbc.queryForObject("select paid_amount from installment where id = ?", BigDecimal.class, installmentId))
				.isEqualByComparingTo("0");
	}

	// -------------------------------------------------------------------------------------
	// V4 — record state/timestamp coherence
	// -------------------------------------------------------------------------------------

	@Test
	void contractCannotBeClosedWithoutTimestampAndReason() {
		assertThatThrownBy(() -> jdbc.update("update contract set status = 'CLOSED' where id = ?", contractId))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void contractCannotBecomeActiveWithoutStartDate() {
		assertThatThrownBy(() -> jdbc.update("update contract set status = 'ACTIVE' where id = ?", contractId))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void installmentPeriodNumberMustBePositive() {
		assertThatThrownBy(() -> insertInstallment(contractId, 0))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void postedPaymentCannotCarryVoidFields() {
		UUID paymentId = UUID.randomUUID();
		assertThatThrownBy(() -> jdbc.update("""
				insert into payment (id, payment_no, contract_id, amount, channel, paid_at, idempotency_key,
					voided_at, void_reason, created_at, updated_at)
					values (?, 'PAY-IT-VOID', ?, 100.00, 'CASH', clock_timestamp(), 'void-key',
						clock_timestamp(), 'silent', clock_timestamp(), clock_timestamp())""", paymentId, contractId))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void voidedPaymentRequiresReasonAndTimestamp() {
		// A valid payment needs allocations summing to its amount (V12 parent-side check), inserted
		// atomically; the test then exercises the V4 void-coherence rule on the committed row.
		UUID paymentId = insertValidPayment(new BigDecimal("100.00"));
		assertThatThrownBy(() -> jdbc.update("update payment set status = 'VOIDED' where id = ?", paymentId))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	// -------------------------------------------------------------------------------------
	// V5 — settlement immutability & settlement_quote snapshot immutability
	// -------------------------------------------------------------------------------------

	@Test
	void settlementCannotBeUpdatedOrDeleted() {
		UUID quoteId = insertQuote();
		UUID settlementId = jdbc.queryForObject("""
				insert into settlement (settlement_no, contract_id, quote_id, cash_received, rebate_amount,
					admin_fee, executed_at, idempotency_key, created_at, updated_at)
					values ('SET-IT-0001', ?, ?, 0.00, 0.00, 0.00, clock_timestamp(), 'set-key',
						clock_timestamp(), clock_timestamp())
					returning id""", UUID.class, contractId, quoteId);

		assertThatThrownBy(() -> jdbc.update("update settlement set cash_received = 5.00 where id = ?", settlementId))
				.isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> jdbc.update("delete from settlement where id = ?", settlementId))
				.isInstanceOf(DataAccessException.class);
	}

	@Test
	void quoteSnapshotColumnsAreImmutableButStatusMayTransition() {
		UUID quoteId = insertQuote();

		// Status transition QUOTED -> EXPIRED is the only legitimate mutation.
		jdbc.update("update settlement_quote set status = 'EXPIRED' where id = ?", quoteId);

		assertThatThrownBy(() -> jdbc.update("update settlement_quote set cash_due = 1.00 where id = ?", quoteId))
				.isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> jdbc.update("delete from settlement_quote where id = ?", quoteId))
				.isInstanceOf(DataAccessException.class);
	}

	// -------------------------------------------------------------------------------------
	// V9 — penalty_accrual immutability and days_late integrity (T2, ADR-012 decision 5,
	// invariant 8: accrual rows are append-only; reductions are penalty_adjustment rows, E5).
	// -------------------------------------------------------------------------------------

	@Test
	void penaltyAccrualCannotBeUpdatedOrDeleted() {
		UUID accrualId = insertAccrual(1);

		assertThatThrownBy(() -> jdbc.update("update penalty_accrual set amount = 1.00 where id = ?", accrualId))
				.isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> jdbc.update("delete from penalty_accrual where id = ?", accrualId))
				.isInstanceOf(DataAccessException.class);
	}

	@Test
	void penaltyAccrualRejectsDaysLateBelowOne() {
		assertThatThrownBy(() -> jdbc.update("""
				insert into penalty_accrual (installment_id, accrual_date, days_late, amount,
					created_at, updated_at)
					values (?, date '2026-03-01', 0, 10.00, clock_timestamp(), clock_timestamp())""",
				installmentId))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void shedlockTableExistsWithTheProvidersExpectedColumns() {
		jdbc.update("insert into shedlock (name, lock_until, locked_at, locked_by) "
				+ "values ('test-lock', clock_timestamp(), clock_timestamp(), 'it-runner')");

		assertThat(jdbc.queryForObject("select locked_by from shedlock where name = 'test-lock'", String.class))
				.isEqualTo("it-runner");

		jdbc.update("delete from shedlock where name = 'test-lock'");
	}

	private UUID insertAccrual(int daysLate) {
		return jdbc.queryForObject("""
				insert into penalty_accrual (installment_id, accrual_date, days_late, amount,
					created_at, updated_at)
					values (?, date '2026-03-01', ?, 10.00, clock_timestamp(), clock_timestamp())
					returning id""", UUID.class, installmentId, daysLate);
	}

	// -------------------------------------------------------------------------------------
	// V12 — parent-side accounting backstops (T25, review CR-05/CR-06/CR-12):
	//   * a journal_entry must have ≥ 2 balanced lines (CR-06),
	//   * a payment must have allocations summing to its amount (CR-06),
	//   * at most one non-reversal entry per (ref_type, ref_id) for current ref types; a reversal
	//     may still share the pair (CR-05, amends ADR-008 d5),
	//   * system_parameter is append-only in the database (CR-12 / X-12).
	//
	// Rejection timing differs by guard, and these tests assert the SQLSTATE so the distinction is
	// pinned (the T25 AC phrase "fails at commit" is literally true only for the first group):
	//   * the two parent-side constraint triggers are DEFERRED, so they raise raise_exception
	//     (SQLSTATE P0001) at COMMIT — asserted by letting the inner statements succeed and catching
	//     the failure from tx.executeWithoutResult itself;
	//   * uq_journal_entry_event raises unique_violation (23505) at the INSERT statement;
	//   * trg_system_parameter_immutable (BEFORE UPDATE/DELETE) raises P0001 at the statement.
	// -------------------------------------------------------------------------------------

	private static final String SQLSTATE_RAISE_EXCEPTION = "P0001";
	private static final String SQLSTATE_UNIQUE_VIOLATION = "23505";

	@Test
	void journalEntryWithZeroLinesIsRejectedAtCommit() {
		UUID entryId = UUID.randomUUID();
		Throwable thrown = catchThrowable(() -> tx.executeWithoutResult(status -> jdbc.update("""
				insert into journal_entry (id, entry_date, ref_type, ref_id, posted_at, created_at)
					values (?, clock_timestamp(), 'TEST', ?, clock_timestamp(), clock_timestamp())""",
				entryId, entryId)));
		// The INSERT itself succeeds; the deferred trigger fires at COMMIT with P0001.
		assertThat(sqlStateOf(thrown)).isEqualTo(SQLSTATE_RAISE_EXCEPTION);
		assertThat(countOf("journal_entry", "id", entryId)).isZero();
	}

	@Test
	void journalEntryWithOneLineIsRejectedAtCommit() {
		UUID entryId = UUID.randomUUID();
		Throwable thrown = catchThrowable(() -> tx.executeWithoutResult(status -> {
			jdbc.update("""
					insert into journal_entry (id, entry_date, ref_type, ref_id, posted_at, created_at)
						values (?, clock_timestamp(), 'TEST', ?, clock_timestamp(), clock_timestamp())""",
					entryId, entryId);
			jdbc.update("""
					insert into journal_line (journal_entry_id, entry_date, account_code, debit, credit, created_at)
						values (?, clock_timestamp(), 'KAS', 500.00, 0, clock_timestamp())""", entryId);
		}));
		assertThat(sqlStateOf(thrown)).isEqualTo(SQLSTATE_RAISE_EXCEPTION);
		assertThat(countOf("journal_entry", "id", entryId)).isZero();
	}

	@Test
	void paymentWithZeroAllocationsIsRejectedAtCommit() {
		UUID paymentId = UUID.randomUUID();
		Throwable thrown = catchThrowable(() -> tx.executeWithoutResult(status -> jdbc.update("""
				insert into payment (id, payment_no, contract_id, amount, channel, paid_at, idempotency_key,
					created_at, updated_at)
					values (?, 'PAY-IT-NOALLOC', ?, 100.00, 'CASH', clock_timestamp(), 'noalloc-key',
						clock_timestamp(), clock_timestamp())""", paymentId, contractId)));
		assertThat(sqlStateOf(thrown)).isEqualTo(SQLSTATE_RAISE_EXCEPTION);
		assertThat(countOf("payment", "id", paymentId)).isZero();
	}

	@Test
	void secondNonReversalEntryForTheSameEventIsRejectedAtStatementTimeWhileAReversalIsAllowed() {
		// First BILLING entry for the installment commits.
		insertBalancedEntry(UUID.randomUUID(), "BILLING", installmentId, null);

		// A second non-reversal BILLING entry for the same ref_id violates uq_journal_entry_event.
		// The unique index is not deferred, so it rejects at the INSERT statement with 23505.
		UUID duplicateId = UUID.randomUUID();
		Throwable thrown = catchThrowable(() -> insertBalancedEntry(duplicateId, "BILLING", installmentId, null));
		assertThat(thrown).isInstanceOf(DataIntegrityViolationException.class);
		assertThat(sqlStateOf(thrown)).isEqualTo(SQLSTATE_UNIQUE_VIOLATION);
		assertThat(countOf("journal_entry", "id", duplicateId)).isZero();

		// A reversal of the original (reversal_of_id set) shares (BILLING, installment) and is allowed.
		UUID originalId = jdbc.queryForObject(
				"select id from journal_entry where ref_type = 'BILLING' and ref_id = ? and reversal_of_id is null",
				UUID.class, installmentId);
		UUID reversalId = UUID.randomUUID();
		insertBalancedEntry(reversalId, "BILLING", installmentId, originalId);
		assertThat(countOf("journal_entry", "id", reversalId)).isEqualTo(1L);
	}

	@Test
	void settlementEventsMayStillPostSeveralEntries() {
		// The uq_journal_entry_event index is scoped to current ref types, leaving SETTLEMENT free
		// for E2 to post more than one entry per event (ADR-008 decision 5, kept open by T25).
		UUID refId = UUID.randomUUID();
		insertBalancedEntry(UUID.randomUUID(), "SETTLEMENT", refId, null);
		insertBalancedEntry(UUID.randomUUID(), "SETTLEMENT", refId, null);
		assertThat(jdbc.queryForObject(
				"select count(*) from journal_entry where ref_type = 'SETTLEMENT' and ref_id = ?",
				Long.class, refId)).isEqualTo(2L);
	}

	@Test
	void systemParameterCannotBeUpdatedOrDeleted() {
		// A V1 seed row stands in for any append-only configuration row. The BEFORE trigger rejects
		// at the UPDATE/DELETE statement (not at commit) with raise_exception (P0001).
		Throwable onUpdate = catchThrowable(() -> jdbc.update(
				"update system_parameter set param_value = '99' where param_key = 'DEFAULT_GRACE_PERIOD_DAYS'"));
		assertThat(onUpdate).isInstanceOf(DataAccessException.class);
		assertThat(sqlStateOf(onUpdate)).isEqualTo(SQLSTATE_RAISE_EXCEPTION);

		Throwable onDelete = catchThrowable(() -> jdbc.update(
				"delete from system_parameter where param_key = 'DEFAULT_GRACE_PERIOD_DAYS'"));
		assertThat(onDelete).isInstanceOf(DataAccessException.class);
		assertThat(sqlStateOf(onDelete)).isEqualTo(SQLSTATE_RAISE_EXCEPTION);
	}

	/** Walk the cause chain to the originating {@link SQLException} and return its SQLSTATE. */
	private static String sqlStateOf(Throwable thrown) {
		assertThat(thrown).as("an exception was expected").isNotNull();
		for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
			if (cause instanceof SQLException sqlException) {
				return sqlException.getSQLState();
			}
		}
		throw new AssertionError("no SQLException in the cause chain of " + thrown, thrown);
	}

	/**
	 * Insert a journal entry with two balanced lines (KAS debit / PIUTANG_POKOK credit) in one
	 * transaction so the V3 and V12 deferred checks pass. {@code reversalOfId} is {@code null} for an
	 * original entry or the id of the entry being reversed.
	 */
	private void insertBalancedEntry(UUID entryId, String refType, UUID refId, UUID reversalOfId) {
		tx.executeWithoutResult(status -> {
			jdbc.update("""
					insert into journal_entry (id, entry_date, ref_type, ref_id, reversal_of_id, posted_at, created_at)
						values (?, clock_timestamp(), ?, ?, ?, clock_timestamp(), clock_timestamp())""",
					entryId, refType, refId, reversalOfId);
			jdbc.update("""
					insert into journal_line (journal_entry_id, entry_date, account_code, debit, credit, created_at)
						values (?, clock_timestamp(), 'KAS', 500.00, 0, clock_timestamp())""", entryId);
			jdbc.update("""
					insert into journal_line (journal_entry_id, entry_date, account_code, debit, credit, created_at)
						values (?, clock_timestamp(), 'PIUTANG_POKOK', 0, 500.00, clock_timestamp())""", entryId);
		});
	}
}