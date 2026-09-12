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

	private long count(String table, String where) {
		String sql = "select count(*) from " + table;
		if (where != null) {
			sql = sql + " where " + where;
		}
		return jdbc.queryForObject(sql, Long.class);
	}
}