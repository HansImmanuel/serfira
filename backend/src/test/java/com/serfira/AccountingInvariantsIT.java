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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
		assertThat(versions).containsExactly("1", "2", "3", "4", "5");
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
		UUID paymentId = insertPayment(new BigDecimal("1000.00"));
		tx.executeWithoutResult(status -> {
			insertAllocation(paymentId, "PRINCIPAL", "600.00");
			insertAllocation(paymentId, "PRINCIPAL", "400.00");
		});
		assertThat(countOf("payment_allocation", "payment_id", paymentId)).isEqualTo(2L);
	}

	@Test
	void mismatchedPaymentAllocationSumIsRejectedAtCommit() {
		UUID paymentId = insertPayment(new BigDecimal("1000.00"));
		assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
			insertAllocation(paymentId, "PRINCIPAL", "600.00");
			insertAllocation(paymentId, "PRINCIPAL", "300.00");
		})).isInstanceOf(RuntimeException.class);
		assertThat(countOf("payment_allocation", "payment_id", paymentId)).isZero();
	}

	@Test
	void interestAllocationBeyondRecognizedAmountIsRejectedAtCommit() {
		// Fixture recognized_interest_amount = 0 — an INTEREST allocation is impossible by itself.
		UUID paymentId = insertPayment(new BigDecimal("100.00"));
		assertThatThrownBy(() -> tx.executeWithoutResult(status ->
				insertAllocation(paymentId, "INTEREST", "100.00")))
				.isInstanceOf(RuntimeException.class);
		assertThat(countOf("payment_allocation", "payment_id", paymentId)).isZero();
	}

	@Test
	void recognitionThenAllocationInSameTransactionCommits() {
		// H-4: recognized_interest update and the allocation are separate statements in one tx.
		UUID paymentId = insertPayment(new BigDecimal("100.00"));
		tx.executeWithoutResult(status -> {
			jdbc.update("update installment set recognized_interest_amount = 100.00 where id = ?", installmentId);
			insertAllocation(paymentId, "INTEREST", "100.00");
		});
		assertThat(countOf("payment_allocation", "payment_id", paymentId)).isEqualTo(1L);
	}

	@Test
	void allocationThenRecognitionInSameTransactionCommits() {
		// H-4 mirrored: allocation first, recognition second — deferred checks must still pass.
		UUID paymentId = insertPayment(new BigDecimal("100.00"));
		tx.executeWithoutResult(status -> {
			insertAllocation(paymentId, "INTEREST", "100.00");
			jdbc.update("update installment set recognized_interest_amount = 100.00 where id = ?", installmentId);
		});
		assertThat(countOf("payment_allocation", "payment_id", paymentId)).isEqualTo(1L);
	}

	@Test
	void penaltyAllocationBeyondEffectiveOutstandingIsRejectedAtCommit() {
		// Fixture penalty_amount = 0: any PENALTY allocation must be rejected at commit.
		UUID paymentId = insertPayment(new BigDecimal("50.00"));
		assertThatThrownBy(() -> tx.executeWithoutResult(status ->
				insertAllocation(paymentId, "PENALTY", "50.00")))
				.isInstanceOf(RuntimeException.class);
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
		UUID paymentId = insertPayment(new BigDecimal("100.00"));
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
}