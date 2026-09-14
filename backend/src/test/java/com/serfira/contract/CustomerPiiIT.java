package com.serfira.contract;

import com.serfira.TestcontainersConfiguration;
import com.serfira.contract.application.CustomerSearchService;
import com.serfira.contract.application.CustomerSummary;
import com.serfira.contract.domain.Customer;
import com.serfira.contract.infrastructure.CustomerRepository;
import com.serfira.shared.security.PiiHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-004 / PII at rest — proves against PostgreSQL 16 that the customer table stores AES-GCM
 * ciphertext (never the raw NIK/phone), that uniqueness/search flow exclusively through the HMAC
 * lookup columns ({@code nik_hash} / {@code phone_lookup}, TS §6.5), and that the
 * {@link CustomerSearchService} resolves customers from raw identifiers only via those lookups.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class CustomerPiiIT {

	private static final String RAW_NIK = "3171012501900001";
	private static final String RAW_PHONE = "08123456789";

	@Autowired
	CustomerRepository customers;

	@Autowired
	CustomerSearchService search;

	@Autowired
	JdbcTemplate jdbc;

	@BeforeEach
	void cleanDomainTables() {
		jdbc.execute("""
				TRUNCATE TABLE
					refresh_token, idempotency_keys, job_run, reconciliation_exception, outbox_events,
					settlement_credit_application, settlement_allocation, penalty_adjustment, penalty_accrual,
					contract_credit_application, contract_credit, payment_allocation, payment,
					settlement_quote, settlement, installment, journal_line, journal_entry,
					contract, asset, customer
				CASCADE""");
	}

	@Test
	void storesCiphertextAndLookupsAtRestAndDecryptsTransparently() {
		customers.saveAndFlush(customer(RAW_NIK, RAW_PHONE, "Budi Santoso"));

		Map<String, Object> row = jdbc.queryForMap(
				"select nik, phone, nik_hash, phone_lookup from customer");
		assertThat(row.get("nik").toString()).startsWith("v1:").isNotEqualTo(RAW_NIK);
		assertThat(row.get("phone").toString()).startsWith("v1:").isNotEqualTo(RAW_PHONE);
		assertThat(row.get("nik_hash").toString()).isEqualTo(PiiHasher.hashNik(RAW_NIK));
		assertThat(row.get("phone_lookup").toString()).isEqualTo(PiiHasher.hashPhone(RAW_PHONE));

		Customer loaded = customers.findByNikHash(PiiHasher.hashNik(RAW_NIK)).orElseThrow();
		assertThat(loaded.getNik()).isEqualTo(RAW_NIK);
		assertThat(loaded.getPhone()).isEqualTo(RAW_PHONE);
		assertThat(loaded.getPhoneLookup()).isEqualTo(PiiHasher.hashPhone(RAW_PHONE));
	}

	@Test
	void duplicateNikIsRejectedThroughNikHashEvenThoughCiphertextsDiffer() {
		customers.saveAndFlush(customer(RAW_NIK, "08111111111", "First"));

		// Same raw NIK, different phone → different ciphertext, same nik_hash → unique violation.
		assertThatThrownBy(() -> customers.saveAndFlush(customer(RAW_NIK, "08222222222", "Second")))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void sharedPhoneIsRejectedThroughPhoneLookup() {
		customers.saveAndFlush(customer("3171012501900002", RAW_PHONE, "First"));

		// Different NIK, same phone → one-phone-per-customer rule (uk_customer_phone_lookup).
		assertThatThrownBy(() -> customers.saveAndFlush(customer("3275015506850002", RAW_PHONE, "Second")))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void searchServiceResolvesRawIdentifiersToTheCustomer() {
		customers.saveAndFlush(customer(RAW_NIK, RAW_PHONE, "Budi Santoso"));

		// NIK with separators/whitespace resolves to the same customer.
		CustomerSummary byNik = search.findByRawNik("3171 0125 0190 0001").orElseThrow();
		assertThat(byNik.fullName()).isEqualTo("Budi Santoso");
		assertThat(byNik.nik()).isEqualTo(RAW_NIK);

		// Non-canonical phone spelling (+62, dashes) resolves identically.
		CustomerSummary byPhone = search.findByRawPhone("+62 812-3456-789").orElseThrow();
		assertThat(byPhone.phone()).isEqualTo(RAW_PHONE);
	}

	@Test
	void searchServiceReturnsEmptyForUnknownRawValues() {
		assertThat(search.findByRawNik("9999999999999999")).isEmpty();
		assertThat(search.findByRawPhone("+6280000000000")).isEmpty();
	}

	@Test
	void noPlaintextPiiExistsInAnyCustomerRow() {
		customers.saveAndFlush(customer(RAW_NIK, RAW_PHONE, "Budi Santoso"));

		List<Map<String, Object>> rows = jdbc.queryForList("select nik, phone from customer");
		assertThat(rows).isNotEmpty();
		for (Map<String, Object> row : rows) {
			assertThat(row.get("nik").toString()).startsWith("v1:").isNotEqualTo(RAW_NIK);
			assertThat(row.get("phone").toString()).startsWith("v1:").isNotEqualTo(RAW_PHONE);
		}

		Integer plaintextHits = jdbc.queryForObject(
				"select count(*) from customer where nik in (?,?) or phone in (?,?)",
				Integer.class, RAW_NIK, RAW_NIK, RAW_PHONE, RAW_PHONE);
		assertThat(plaintextHits).isZero();
	}

	private Customer customer(String nik, String phone, String fullName) {
		return new Customer(fullName, nik, PiiHasher.hashNik(nik), phone, PiiHasher.hashPhone(phone), "Jakarta");
	}
}