package com.serfira.ledger.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The chart-of-accounts vocabulary is the contract between posting code and the COA rows seeded by V1
 * (03_DOMAIN_MODEL.md §1.12). These tests pin the documented accounts so a silent rename or removal
 * cannot slip into a posting rule; {@code LedgerPostingIT} separately asserts that every constant exists
 * in the database.
 */
class LedgerAccountTest {

	@Test
	void exposesExactlyTheDocumentedChartOfAccounts() {
		assertThat(LedgerAccount.values()).extracting(Enum::name).containsExactly(
				"KAS",
				"PIUTANG_POKOK",
				"PIUTANG_BUNGA",
				"PIUTANG_DENDA",
				"TITIPAN_NASABAH",
				"PENDAPATAN_BUNGA",
				"PENDAPATAN_DENDA",
				"PENDAPATAN_ADMIN",
				"DISKON_PELUNASAN",
				"BIAYA_PENGHAPUSAN_PIUTANG");
	}

	@Test
	void everyAccountCodeIsThePersistedChartOfAccountsCode() {
		for (LedgerAccount account : LedgerAccount.values()) {
			assertThat(account.code()).isEqualTo(account.name());
			assertThat(account.code()).matches("[A-Z_]+");
		}
	}
}
