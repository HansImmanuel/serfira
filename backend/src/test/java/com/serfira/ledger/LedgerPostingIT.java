package com.serfira.ledger;

import com.serfira.TestcontainersConfiguration;
import com.serfira.contract.api.ContractResponse;
import com.serfira.contract.api.CreateAssetRequest;
import com.serfira.contract.api.CreateContractRequest;
import com.serfira.contract.api.CreateCustomerRequest;
import com.serfira.contract.application.ContractCommandService;
import com.serfira.contract.domain.AssetType;
import com.serfira.contract.domain.ContractStatus;
import com.serfira.contract.domain.InterestScheme;
import com.serfira.ledger.application.LedgerPostingService;
import com.serfira.ledger.domain.LedgerAccount;
import com.serfira.ledger.domain.LedgerPosting;
import com.serfira.ledger.domain.LedgerPostingException;
import com.serfira.ledger.domain.LedgerPostingLine;
import com.serfira.ledger.domain.LedgerRefType;
import com.serfira.shared.audit.AuditContext;
import com.serfira.shared.clock.Clock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C1 — the ledger write path against a real PostgreSQL 16 (Testcontainers): a balanced posting commits as
 * one immutable entry, the event guard keeps posting idempotent per {@code (ref_type, ref_id)}, a posting
 * cannot escape the caller's transaction, the request actor is recorded, and contract activation posts its
 * disbursement entry exactly once (TS §3, ADR-008).
 *
 * <p>Commit-time behaviour is exercised through {@link TransactionTemplate} instead of a rolling-back
 * {@code @Transactional} test method, because the database balance invariant is a DEFERRED constraint
 * trigger that only fires at COMMIT (V3 {@code trg_journal_entry_balance_deferred}).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class LedgerPostingIT {

	private static final String NIK = "3171012501900001";
	private static final String PHONE = "08123456789";
	private static final LocalDate PLANNED_START = LocalDate.of(2026, 1, 31);
	private static final OffsetDateTime BUSINESS_DATE = OffsetDateTime.of(2026, 1, 31, 0, 0, 0, 0,
			ZoneOffset.ofHours(7));
	private static final UUID SYSTEM_USER_ID = AuditContext.SYSTEM_USER_ID;

	@Autowired
	LedgerPostingService ledger;

	@Autowired
	ContractCommandService contracts;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	TransactionTemplate tx;

	@Autowired
	AuditContext auditContext;

	@Autowired
	Clock clock;

	@BeforeEach
	void cleanSharedDatabase() {
		truncateDomainTables();
		jdbc.update("delete from app_user where id <> ?", SYSTEM_USER_ID);
	}

	@AfterEach
	void leaveCleanSharedDatabase() {
		// Leaves nothing behind that references this suite's actor: another suite (e.g.
		// ResourceServerSecurityIT) deletes app_user rows, and a journal/counter row carrying our
		// actor id would break that cleanup.
		truncateDomainTables();
		jdbc.update("delete from app_user where id <> ?", SYSTEM_USER_ID);
	}

	@Test
	void postingPersistsOneBalancedEntryWithItsLines() {
		UUID contractId = insertContract();
		OffsetDateTime before = clock.now();
		UUID[] entryId = new UUID[1];

		tx.executeWithoutResult(status -> entryId[0] = ledger.post(disbursement(contractId, "16000000.00")).getId());

		OffsetDateTime after = clock.now();
		assertThat(count("journal_entry")).isEqualTo(1L);
		assertThat(count("journal_line")).isEqualTo(2L);

		Map<String, Object> entry = entryRow(entryId[0]);
		assertThat(entry.get("ref_type")).isEqualTo("CONTRACT_ACTIVATION");
		assertThat(entry.get("ref_id")).isEqualTo(contractId);
		assertThat(entry.get("description")).isEqualTo("Disbursement");
		assertThat(entry.get("reversal_of_id")).isNull();
		// entry_date is the event's business date; posted_at is the recording instant from the app clock.
		assertThat(((OffsetDateTime) entry.get("entry_date")).toInstant()).isEqualTo(BUSINESS_DATE.toInstant());
		assertThat(((OffsetDateTime) entry.get("posted_at")).toInstant())
				.isBetween(before.toInstant(), after.toInstant());
		// No actor is bound in this suite, so the seeded SYSTEM principal is recorded (Addendum §3.3).
		assertThat(entry.get("created_by")).isEqualTo(SYSTEM_USER_ID);

		assertThat(debitOf(entryId[0])).isEqualByComparingTo("16000000.00");
		assertThat(creditOf(entryId[0])).isEqualByComparingTo("16000000.00");
		assertThat(jdbc.queryForList(
				"select account_code from journal_line where journal_entry_id = ? order by account_code",
				String.class, entryId[0])).containsExactly("KAS", "PIUTANG_POKOK");
		// journal_line.entry_date is denormalized from the entry (V1), and the contract scope is kept for
		// the receivable reconciliation (Addendum §7.1, check C).
		assertThat(jdbc.queryForObject("""
				select count(*) from journal_line
				where journal_entry_id = ? and entry_date = ? and contract_id = ?
				""", Long.class, entryId[0], BUSINESS_DATE, contractId)).isEqualTo(2L);
	}

	@Test
	void activationPostsTheDisbursementEntryExactlyOnce() {
		ContractResponse draft = contracts.create("ledger-it-key", contractRequest());

		ContractResponse activated = contracts.activate(draft.id(), null);

		assertThat(activated.status()).isEqualTo(ContractStatus.ACTIVE);
		UUID entryId = onlyEntryFor(LedgerRefType.CONTRACT_ACTIVATION, draft.id());
		// TS §3 "Aktivasi kontrak (disburse)": receivable up, cash out — for the contract's principal.
		assertThat(debitOf(entryId)).isEqualByComparingTo("16000000.00");
		assertThat(creditOf(entryId)).isEqualByComparingTo("16000000.00");
		assertThat(jdbc.queryForObject(
				"select debit from journal_line where journal_entry_id = ? and account_code = 'PIUTANG_POKOK'",
				BigDecimal.class, entryId)).isEqualByComparingTo("16000000.00");
		assertThat(jdbc.queryForObject(
				"select credit from journal_line where journal_entry_id = ? and account_code = 'KAS'",
				BigDecimal.class, entryId)).isEqualByComparingTo("16000000.00");
		// The accounting date is the contractual start date, not the wall-clock time of the request.
		assertThat(((OffsetDateTime) entryRow(entryId).get("entry_date")).toInstant())
				.isEqualTo(PLANNED_START.atStartOfDay(ZoneOffset.ofHours(7)).toInstant());
		assertThat((String) entryRow(entryId).get("description")).contains(activated.contractNo());

		// Activation is idempotent by state (ADR-006), so a repeat must not post a second disbursement.
		contracts.activate(draft.id(), null);

		assertThat(countEntriesFor(LedgerRefType.CONTRACT_ACTIVATION, draft.id())).isEqualTo(1L);
		assertThat(count("journal_entry")).isEqualTo(1L);
		assertThat(count("journal_line")).isEqualTo(2L);
	}

	@Test
	void aSecondNonReversalPostingForTheSameEventIsRejected() {
		UUID contractId = insertContract();
		tx.executeWithoutResult(status -> ledger.post(disbursement(contractId, "100.00")));

		assertThatThrownBy(() -> tx.executeWithoutResult(status -> ledger.post(disbursement(contractId, "100.00"))))
				.isInstanceOf(LedgerPostingException.class)
				.hasMessageContaining("already posted");

		assertThat(count("journal_entry")).isEqualTo(1L);
		assertThat(count("journal_line")).isEqualTo(2L);
	}

	@Test
	void aReversalForAnAlreadyPostedEventIsAccepted() {
		UUID contractId = insertContract();
		UUID[] originalEntryId = new UUID[1];
		tx.executeWithoutResult(status ->
				originalEntryId[0] = ledger.post(disbursement(contractId, "100.00")).getId());

		// Same (ref_type, ref_id) but marked as a correction: the documented exception to the guard (E4).
		LedgerPosting reversal = new LedgerPosting(LedgerRefType.CONTRACT_ACTIVATION, contractId, BUSINESS_DATE,
				"Reversal of disbursement", originalEntryId[0], List.of(
						LedgerPostingLine.debit(LedgerAccount.KAS, new BigDecimal("100.00"), contractId),
						LedgerPostingLine.credit(LedgerAccount.PIUTANG_POKOK, new BigDecimal("100.00"), contractId)));
		tx.executeWithoutResult(status -> ledger.post(reversal));

		assertThat(count("journal_entry")).isEqualTo(2L);
		assertThat(jdbc.queryForObject("select count(*) from journal_entry where reversal_of_id = ?", Long.class,
				originalEntryId[0])).isEqualTo(1L);
	}

	@Test
	void postingOutsideATransactionIsRejected() {
		UUID contractId = insertContract();

		// ADR-008: posting is MANDATORY-transactional, so a caller cannot write money movement on its own.
		assertThatThrownBy(() -> ledger.post(disbursement(contractId, "100.00")))
				.isInstanceOf(IllegalTransactionStateException.class);

		assertThat(count("journal_entry")).isZero();
	}

	@Test
	void aRolledBackCallerTransactionLeavesNoJournalRows() {
		UUID contractId = insertContract();

		tx.executeWithoutResult(status -> {
			ledger.post(disbursement(contractId, "100.00"));
			status.setRollbackOnly();
		});

		// ADR-002: the journal and the business write commit or roll back together — never a lone entry.
		assertThat(count("journal_entry")).isZero();
		assertThat(count("journal_line")).isZero();
	}

	@Test
	void theActorOfTheRequestIsRecordedOnTheEntry() {
		UUID contractId = insertContract();
		UUID actorId = insertActor();
		UUID[] entryId = new UUID[1];

		auditContext.runAs(actorId, () -> tx.executeWithoutResult(status ->
				entryId[0] = ledger.post(disbursement(contractId, "100.00")).getId()));

		assertThat(jdbc.queryForObject("select created_by from journal_entry where id = ?", UUID.class, entryId[0]))
				.isEqualTo(actorId);
	}

	@Test
	void everyLedgerAccountExistsInTheSeededChartOfAccounts() {
		List<String> seeded = jdbc.queryForList("select code from accounts", String.class);

		assertThat(seeded).contains(
				Arrays.stream(LedgerAccount.values()).map(LedgerAccount::code).toArray(String[]::new));
	}

	@Test
	void anUnknownAccountCodeIsRejectedByTheForeignKeyBackstop() {
		UUID entryId = UUID.randomUUID();

		// The enum makes this impossible through the service; raw SQL proves the FK still guards the table.
		assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
			jdbc.update("""
					insert into journal_entry (id, entry_date, ref_type, ref_id, posted_at, created_at)
						values (?, clock_timestamp(), 'PAYMENT', ?, clock_timestamp(), clock_timestamp())
					""", entryId, entryId);
			jdbc.update("""
					insert into journal_line (journal_entry_id, entry_date, account_code, debit, credit, created_at)
						values (?, clock_timestamp(), 'KAS', 100.00, 0, clock_timestamp())
					""", entryId);
			jdbc.update("""
					insert into journal_line (journal_entry_id, entry_date, account_code, debit, credit, created_at)
						values (?, clock_timestamp(), 'NOT_AN_ACCOUNT', 0, 100.00, clock_timestamp())
					""", entryId);
		})).isInstanceOf(DataIntegrityViolationException.class);

		assertThat(count("journal_entry")).isZero();
		assertThat(count("journal_line")).isZero();
	}

	// -------------------------------------------------------------------------------------
	// Fixtures & helpers
	// -------------------------------------------------------------------------------------

	private static LedgerPosting disbursement(UUID contractId, String principal) {
		return LedgerPosting.of(LedgerRefType.CONTRACT_ACTIVATION, contractId, BUSINESS_DATE, "Disbursement", List.of(
				LedgerPostingLine.debit(LedgerAccount.PIUTANG_POKOK, new BigDecimal(principal), contractId),
				LedgerPostingLine.credit(LedgerAccount.KAS, new BigDecimal(principal), contractId)));
	}

	private static CreateContractRequest contractRequest() {
		return new CreateContractRequest(
				new CreateCustomerRequest("Budi Santoso", NIK, PHONE, "Jakarta"),
				new CreateAssetRequest(AssetType.MOTORCYCLE, "Honda", "Beat", null, "B1234XY"),
				new BigDecimal("20000000.00"), new BigDecimal("4000000.00"), 12, InterestScheme.FLAT,
				new BigDecimal("0.0150"), PLANNED_START);
	}

	/**
	 * Explicit typed reads: a TIMESTAMPTZ comes back in the session offset (UTC in the container), so the
	 * assertions compare instants rather than local representations.
	 */
	private Map<String, Object> entryRow(UUID entryId) {
		return jdbc.queryForObject("select * from journal_entry where id = ?", (rs, rowNum) -> {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("ref_type", rs.getString("ref_type"));
			row.put("ref_id", rs.getObject("ref_id", UUID.class));
			row.put("entry_date", rs.getObject("entry_date", OffsetDateTime.class));
			row.put("posted_at", rs.getObject("posted_at", OffsetDateTime.class));
			row.put("description", rs.getString("description"));
			row.put("reversal_of_id", rs.getObject("reversal_of_id", UUID.class));
			row.put("created_by", rs.getObject("created_by", UUID.class));
			return row;
		}, entryId);
	}

	private UUID onlyEntryFor(LedgerRefType refType, UUID refId) {
		List<UUID> ids = jdbc.queryForList("select id from journal_entry where ref_type = ? and ref_id = ?",
				UUID.class, refType.name(), refId);
		assertThat(ids).hasSize(1);
		return ids.get(0);
	}

	private long countEntriesFor(LedgerRefType refType, UUID refId) {
		return jdbc.queryForObject("select count(*) from journal_entry where ref_type = ? and ref_id = ?", Long.class,
				refType.name(), refId);
	}

	private BigDecimal debitOf(UUID entryId) {
		return jdbc.queryForObject("select sum(debit) from journal_line where journal_entry_id = ?", BigDecimal.class,
				entryId);
	}

	private BigDecimal creditOf(UUID entryId) {
		return jdbc.queryForObject("select sum(credit) from journal_line where journal_entry_id = ?", BigDecimal.class,
				entryId);
	}

	private long count(String table) {
		return jdbc.queryForObject("select count(*) from " + table, Long.class);
	}

	private UUID insertActor() {
		return jdbc.queryForObject("""
				insert into app_user (username, password_hash, full_name, role, is_active, created_at, updated_at)
					values (?, 'test-hash', 'Ledger IT Actor', 'FINANCE', TRUE, clock_timestamp(), clock_timestamp())
					returning id
				""", UUID.class, "ledger-it-actor-" + UUID.randomUUID());
	}

	/** Minimal contract fixture: the ledger only needs a real contract id for the line's foreign key. */
	private UUID insertContract() {
		UUID customerId = jdbc.queryForObject("""
				insert into customer (full_name, nik, nik_hash, phone, phone_lookup, created_at, updated_at)
					values ('IT Customer', 'it-nik-cipher-ledger', repeat('l', 64), 'it-phone-cipher-ledger',
						repeat('m', 64), clock_timestamp(), clock_timestamp())
					returning id
				""", UUID.class);
		UUID assetId = jdbc.queryForObject("""
				insert into asset (asset_type, brand, model, created_at, updated_at)
					values ('MOTORCYCLE', 'Honda', 'Beat', clock_timestamp(), clock_timestamp())
					returning id
				""", UUID.class);
		return jdbc.queryForObject("""
				insert into contract (contract_no, customer_id, asset_id, asset_price, principal, down_payment,
					tenor_months, interest_scheme, interest_rate, grace_period_days, penalty_rate_daily,
					created_at, updated_at)
					values (?, ?, ?, 1000000.00, 900000.00, 100000.00, 12, 'FLAT', 0.0150, 3, 0.0010,
						clock_timestamp(), clock_timestamp())
					returning id
				""", UUID.class, "MF-LGR-" + UUID.randomUUID().toString().replace("-", "").substring(0, 18),
				customerId, assetId);
	}

	private void truncateDomainTables() {
		// Shared Testcontainers hygiene (same recipe as the contract ITs): TRUNCATE does not fire the
		// row-level/deferred triggers, and app_user keeps the seeded SYSTEM row.
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
}
