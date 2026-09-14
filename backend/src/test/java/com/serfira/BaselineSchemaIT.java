package com.serfira;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Sprint 0 / A3 — proves Flyway applies V1__baseline.sql on a clean PostgreSQL 16 and that
 * seeds + key schema invariants are enforced by the database.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class BaselineSchemaIT {

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void flywayAppliesBaselineMigration() {
		Long applied = jdbc.queryForObject(
				"select count(*) from flyway_schema_history where version = '1' and success", Long.class);
		assertThat(applied).isEqualTo(1L);
	}

	@Test
	void allDomainTablesExist() {
		List<String> tables = jdbc.queryForList(
				"select tablename from pg_catalog.pg_tables where schemaname = 'public'", String.class);

		assertThat(tables).contains(
				"app_user", "customer", "asset", "contract", "installment",
				"accounts", "journal_entry", "journal_line",
				"payment", "payment_allocation",
				"penalty_accrual", "penalty_adjustment",
				"contract_credit", "contract_credit_application",
				"settlement_quote", "settlement", "settlement_allocation", "settlement_credit_application",
				"document_number_counter", "system_parameter", "idempotency_keys", "refresh_token",
				"job_run", "reconciliation_exception", "outbox_events");
	}

	@Test
	void baselineSeedsArePresent() {
		assertThat(count("app_user", "username = 'SYSTEM'")).isEqualTo(1L);
		assertThat(count("accounts", null)).isEqualTo(10L);
		assertThat(count("system_parameter", null)).isEqualTo(6L);
	}

	@Test
	void systemParameterPortfolioValuesAreSeeded() {
		String grace = jdbc.queryForObject(
				"select param_value from system_parameter where param_key = 'DEFAULT_GRACE_PERIOD_DAYS'", String.class);
		String penaltyRate = jdbc.queryForObject(
				"select param_value from system_parameter where param_key = 'DEFAULT_PENALTY_RATE_DAILY'", String.class);
		String rebate = jdbc.queryForObject(
				"select param_value from system_parameter where param_key = 'SETTLEMENT_REBATE_RATE'", String.class);

		assertThat(grace).isEqualTo("3");
		assertThat(penaltyRate).isEqualTo("0.0010");
		assertThat(rebate).isEqualTo("0.5000");
	}

	@Test
	void chartOfAccountsIsSeededIncludingCreditAndWriteOffAccounts() {
		List<String> codes = jdbc.queryForList("select code from accounts order by code", String.class);
		assertThat(codes).contains("KAS", "PIUTANG_POKOK", "PIUTANG_BUNGA", "PIUTANG_DENDA", "TITIPAN_NASABAH",
				"PENDAPATAN_BUNGA", "PENDAPATAN_DENDA", "PENDAPATAN_ADMIN", "DISKON_PELUNASAN",
				"BIAYA_PENGHAPUSAN_PIUTANG");
	}

	@Test
	void journalEntriesCannotBeUpdatedOrDeleted() {
		UUID id = UUID.randomUUID();
		jdbc.update("insert into journal_entry (id, entry_date, ref_type, ref_id, posted_at, created_at) "
				+ "values (?, clock_timestamp(), 'TEST', ?, clock_timestamp(), clock_timestamp())", id, id);

		assertThatThrownBy(() -> jdbc.update("update journal_entry set description = 'tampered' where id = ?", id))
				.isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> jdbc.update("delete from journal_entry where id = ?", id))
				.isInstanceOf(DataAccessException.class);
	}

	@Test
	void contractInvariantIsEnforcedByDatabase() {
		assertThatThrownBy(() -> jdbc.update(
				"insert into contract (contract_no, customer_id, asset_id, asset_price, principal, down_payment, "
						+ "tenor_months, interest_scheme, interest_rate, grace_period_days, penalty_rate_daily, "
						+ "created_at, updated_at) "
						+ "values ('MF-202601-9999', gen_random_uuid(), gen_random_uuid(), 1000.00, 900.00, 200.00, "
						+ "12, 'FLAT', 0.0150, 3, 0.0010, clock_timestamp(), clock_timestamp())"))
				.isInstanceOf(DataAccessException.class);
	}

	@Test
	void idempotencyKeyIsUniqueWithinEndpoint() {
		jdbc.update("insert into idempotency_keys (key, endpoint, created_at, updated_at) "
				+ "values ('k1', '/api/v1/payments', clock_timestamp(), clock_timestamp())");

		assertThatThrownBy(() -> jdbc.update("insert into idempotency_keys (key, endpoint, created_at, updated_at) "
				+ "values ('k1', '/api/v1/payments', clock_timestamp(), clock_timestamp())"))
				.isInstanceOf(DataAccessException.class);
	}

	@Test
	void postedPaymentCannotReuseAPaymentIdempotencyKey() {
		String firstPayment = """
				with c as (
					insert into customer (full_name, nik, nik_hash, phone, phone_lookup, created_at, updated_at)
					values ('Test Customer 1', 'dummy-nik-cipher-1', repeat('a', 64), 'dummy-phone-cipher-1', repeat('p', 64),
						clock_timestamp(), clock_timestamp())
					returning id
				), a as (
					insert into asset (asset_type, brand, model, created_at, updated_at)
					values ('MOTORCYCLE', 'Honda', 'Beat', clock_timestamp(), clock_timestamp())
					returning id
				), k as (
					insert into contract (contract_no, customer_id, asset_id, asset_price, principal, down_payment,
						tenor_months, interest_scheme, interest_rate, grace_period_days, penalty_rate_daily,
						created_at, updated_at)
					select 'MF-TEST-0001', c.id, a.id, 1000.00, 900.00, 100.00, 12, 'FLAT', 0.0150, 3, 0.0010,
						clock_timestamp(), clock_timestamp()
					from c, a
					returning id
				)
				insert into payment (payment_no, contract_id, amount, channel, paid_at, idempotency_key,
					created_at, updated_at)
				select 'PAY-X-0001', k.id, 100.00, 'CASH', clock_timestamp(), 'pay-key-1',
					clock_timestamp(), clock_timestamp()
				from k
				""";
		jdbc.update(firstPayment);

		// same key while both are POSTED → rejected by the partial unique index
		assertThatThrownBy(() -> jdbc.update(firstPayment
				.replace("'PAY-X-0001'", "'PAY-X-0002'")
				.replace("repeat('a', 64)", "repeat('b', 64)")
				.replace("repeat('p', 64)", "repeat('q', 64)")
				.replace("'MF-TEST-0001'", "'MF-TEST-0002'")))
				.isInstanceOf(DataAccessException.class);

		// voiding the first payment frees the key for a retry
		jdbc.update("update payment set status = 'VOIDED', voided_at = clock_timestamp(), "
				+ "void_reason = 'test' where payment_no = 'PAY-X-0001'");
		jdbc.update(firstPayment
				.replace("'PAY-X-0001'", "'PAY-X-0002'")
				.replace("repeat('a', 64)", "repeat('b', 64)")
				.replace("repeat('p', 64)", "repeat('q', 64)")
				.replace("'MF-TEST-0001'", "'MF-TEST-0002'"));
	}

	@Test
	void settlementIdempotencyKeyIsUnique() {
		String settlementInsert = """
				with c as (
					insert into customer (full_name, nik, nik_hash, phone, phone_lookup, created_at, updated_at)
					values ('Test Customer 2', 'dummy-nik-cipher-2', repeat('c', 64), 'dummy-phone-cipher-2', repeat('r', 64),
						clock_timestamp(), clock_timestamp())
					returning id
				), a as (
					insert into asset (asset_type, brand, model, created_at, updated_at)
					values ('MOTORCYCLE', 'Yamaha', 'Nmax', clock_timestamp(), clock_timestamp())
					returning id
				), k as (
					insert into contract (contract_no, customer_id, asset_id, asset_price, principal, down_payment,
						tenor_months, interest_scheme, interest_rate, grace_period_days, penalty_rate_daily,
						created_at, updated_at)
					select 'MF-TEST-0003', c.id, a.id, 1000.00, 900.00, 100.00, 12, 'FLAT', 0.0150, 3, 0.0010,
						clock_timestamp(), clock_timestamp()
					from c, a
					returning id
				), q as (
					insert into settlement_quote (quote_no, contract_id, quoted_at, valid_until, contract_version,
						outstanding_principal, unpaid_billed_interest, accrued_interest, penalty_outstanding,
						rebate_amount, admin_fee, available_credit, gross_amount, cash_due, created_at, updated_at)
					select 'Q-TEST-0001', k.id, clock_timestamp(), clock_timestamp() + interval '15 minutes', 0,
						900.00, 0.00, 0.00, 0.00, 0.00, 0.00, 0.00, 900.00, 900.00,
						clock_timestamp(), clock_timestamp()
					from k
					returning id
				)
				insert into settlement (settlement_no, contract_id, quote_id, cash_received, rebate_amount,
					admin_fee, executed_at, idempotency_key, created_at, updated_at)
				select 'SET-X-0001', k.id, q.id, 900.00, 0.00, 0.00, clock_timestamp(), 'set-key-1',
					clock_timestamp(), clock_timestamp()
				from k, q
				""";
		jdbc.update(settlementInsert);

		assertThatThrownBy(() -> jdbc.update(settlementInsert
				.replace("'SET-X-0001'", "'SET-X-0002'")
				.replace("'Q-TEST-0001'", "'Q-TEST-0002'")
				.replace("repeat('c', 64)", "repeat('d', 64)")
				.replace("repeat('r', 64)", "repeat('s', 64)")
				.replace("'MF-TEST-0003'", "'MF-TEST-0004'")))
				.isInstanceOf(DataAccessException.class);
	}

	private long count(String table, String where) {
		String sql = "select count(*) from " + table;
		if (where != null) {
			sql = sql + " where " + where;
		}
		return jdbc.queryForObject(sql, Long.class);
	}
}