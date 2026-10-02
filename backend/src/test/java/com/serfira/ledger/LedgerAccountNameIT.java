package com.serfira.ledger;

import com.serfira.TestcontainersConfiguration;
import com.serfira.ledger.domain.LedgerAccount;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins {@link LedgerAccount#displayName()} to the seeded {@code accounts.name} (V1). The contract statement
 * (T9) renders the account name from the enum rather than joining the {@code accounts} table, so the two
 * must agree: if a future migration renames a seeded account, this test fails instead of letting the
 * statement silently diverge from the chart of accounts (ADR-013 A-8).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class LedgerAccountNameIT {

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void everyEnumDisplayNameMatchesTheSeededAccountName() {
		for (LedgerAccount account : LedgerAccount.values()) {
			String seededName = jdbc.queryForObject(
					"select name from accounts where code = ?", String.class, account.code());
			assertThat(seededName)
					.as("accounts.name for code %s", account.code())
					.isEqualTo(account.displayName());
		}
	}

	@Test
	void everyEnumCodeExistsInTheSeededChart() {
		for (LedgerAccount account : LedgerAccount.values()) {
			Integer count = jdbc.queryForObject(
					"select count(*) from accounts where code = ?", Integer.class, account.code());
			assertThat(count).as("accounts row for code %s", account.code()).isEqualTo(1);
		}
	}
}
