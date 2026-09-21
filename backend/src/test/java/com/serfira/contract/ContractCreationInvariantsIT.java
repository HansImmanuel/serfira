package com.serfira.contract;

import com.serfira.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B5 — the V7 database backstops for contract creation, exercised with raw SQL so the constraints
 * themselves are what gets tested (the service-level guard is covered in {@code ContractApiIT}):
 *
 * <ul>
 *   <li>invariant 18 — at most one live (DRAFT | ACTIVE) contract per {@code (customer_id, asset_id)}
 *       ({@code uq_contract_live_asset}),</li>
 *   <li>retry safety — at most one contract per {@code idempotency_key}
 *       ({@code uq_contract_idempotency}), mirroring {@code uq_payment_idempotency},</li>
 *   <li>regression — the V4 DRAFT coherence rule is untouched by V6/V7.</li>
 * </ul>
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ContractCreationInvariantsIT {

	private static final java.sql.Date PLANNED_START = java.sql.Date.valueOf("2026-01-31");

	@Autowired
	JdbcTemplate jdbc;

	@BeforeEach
	void cleanDomainTables() {
		truncateDomainTables();
	}

	@AfterEach
	void leaveCleanSharedDatabase() {
		truncateDomainTables();
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

	@Test
	void secondLiveContractForTheSameCustomerAndAssetIsRejected() {
		UUID customerId = insertCustomer('a');
		UUID assetId = insertAsset();

		insertContract("MF-IT-0001", customerId, assetId, "DRAFT", null, "key-1");

		assertThatThrownBy(() -> insertContract("MF-IT-0002", customerId, assetId, "DRAFT", null, "key-2"))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThat(countContracts()).isEqualTo(1L);
	}

	@Test
	void liveUniquenessAlsoCoversActiveContracts() {
		UUID customerId = insertCustomer('a');
		UUID assetId = insertAsset();
		insertContract("MF-IT-0001", customerId, assetId, "ACTIVE", PLANNED_START, "key-1");

		assertThatThrownBy(() -> insertContract("MF-IT-0002", customerId, assetId, "DRAFT", null, "key-2"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void uniquenessIsReleasedWhenAContractCloses() {
		UUID customerId = insertCustomer('a');
		UUID assetId = insertAsset();
		UUID firstContractId = insertContract("MF-IT-0001", customerId, assetId, "DRAFT", null, "key-1");

		// Closing (maturity/settlement) frees the asset for a new financing of the same customer.
		jdbc.update("""
				update contract set status = 'CLOSED', closed_at = clock_timestamp(), closed_reason = 'SETTLEMENT'
				where id = ?
				""", firstContractId);
		insertContract("MF-IT-0002", customerId, assetId, "DRAFT", null, "key-2");

		assertThat(countContracts()).isEqualTo(2L);
	}

	@Test
	void theSameAssetMayBeFinancedForAnotherCustomer() {
		// Repossession / resale: the asset is keyed together with the customer, not alone.
		UUID firstCustomerId = insertCustomer('a');
		UUID secondCustomerId = insertCustomer('b');
		UUID assetId = insertAsset();

		insertContract("MF-IT-0001", firstCustomerId, assetId, "ACTIVE", PLANNED_START, "key-1");
		insertContract("MF-IT-0002", secondCustomerId, assetId, "DRAFT", null, "key-2");

		assertThat(countContracts()).isEqualTo(2L);
	}

	@Test
	void duplicateIdempotencyKeyIsRejectedEvenAcrossDifferentAssets() {
		UUID customerId = insertCustomer('a');
		insertContract("MF-IT-0001", customerId, insertAsset(), "DRAFT", null, "same-key");

		assertThatThrownBy(() -> insertContract("MF-IT-0002", customerId, insertAsset(), "DRAFT", null, "same-key"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void contractsWithoutAnIdempotencyKeyRemainInsertable() {
		// Pre-B5 rows and raw-SQL fixtures have no key; the unique index is partial on purpose.
		UUID firstCustomerId = insertCustomer('a');
		UUID secondCustomerId = insertCustomer('b');

		insertContract("MF-IT-0001", firstCustomerId, insertAsset(), "DRAFT", null, null);
		insertContract("MF-IT-0002", secondCustomerId, insertAsset(), "DRAFT", null, null);

		assertThat(countContracts()).isEqualTo(2L);
	}

	@Test
	void draftCoherenceRuleIsUnchanged() {
		// V4 ck_contract_draft_coherence still forbids an effective start date on a DRAFT.
		UUID customerId = insertCustomer('a');
		UUID assetId = insertAsset();

		assertThatThrownBy(() -> insertContract("MF-IT-0001", customerId, assetId, "DRAFT", PLANNED_START, "key-1"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	private UUID insertCustomer(char hashChar) {
		return jdbc.queryForObject("""
				insert into customer (full_name, nik, nik_hash, phone, phone_lookup, created_at, updated_at)
					values (?, ?, repeat(?, 64), ?, repeat(?, 64), clock_timestamp(), clock_timestamp())
					returning id
				""", UUID.class,
				"IT Customer " + hashChar, "it-nik-cipher-" + hashChar, String.valueOf(hashChar),
				"it-phone-cipher-" + hashChar, String.valueOf(hashChar));
	}

	private UUID insertAsset() {
		return jdbc.queryForObject("""
				insert into asset (asset_type, brand, model, created_at, updated_at)
					values ('MOTORCYCLE', 'Honda', 'Beat', clock_timestamp(), clock_timestamp())
					returning id
				""", UUID.class);
	}

	private UUID insertContract(String contractNo, UUID customerId, UUID assetId, String status,
			java.sql.Date startDate, String idempotencyKey) {
		return jdbc.queryForObject("""
				insert into contract (contract_no, customer_id, asset_id, asset_price, principal, down_payment,
					tenor_months, interest_scheme, interest_rate, grace_period_days, penalty_rate_daily,
					planned_start_date, idempotency_key, status, start_date, created_at, updated_at)
					values (?, ?, ?, 1000.00, 900.00, 100.00, 12, 'FLAT', 0.0150, 3, 0.0010,
						DATE '2026-01-31', ?, ?, ?, clock_timestamp(), clock_timestamp())
					returning id
				""", UUID.class, contractNo, customerId, assetId, idempotencyKey, status, startDate);
	}

	private long countContracts() {
		return jdbc.queryForObject("select count(*) from contract", Long.class);
	}
}