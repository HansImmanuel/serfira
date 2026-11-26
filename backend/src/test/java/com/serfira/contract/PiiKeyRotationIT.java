package com.serfira.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.serfira.SerfiraCoreApplication;
import com.serfira.contract.api.CreateAssetRequest;
import com.serfira.contract.api.CreateContractRequest;
import com.serfira.contract.api.CreateCustomerRequest;
import com.serfira.contract.application.ContractCommandService;
import com.serfira.contract.application.CustomerSearchService;
import com.serfira.contract.domain.AssetType;
import com.serfira.contract.domain.InterestScheme;
import com.serfira.shared.error.ConflictException;
import com.serfira.shared.security.LegacyV1Envelope;
import com.serfira.shared.security.PiiHasher;
import com.serfira.shared.security.PiiSecuritySupport;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * T28a / ADR-025 acceptance: a real rotation. Context A runs on the test profile's single key ({@code k1}) and writes
 * customers — through the create flow ({@code v2:k1}) and, for a pre-ring row, as a {@code v1:} envelope. Context
 * B then starts on the same database with {@code k2} added and active: every {@code k1} customer is still read,
 * found by NIK and phone, reused by contract create and protected by the 409 rules, while new rows are
 * {@code v2:k2}.
 *
 * <p>Both contexts rebind the JVM-global {@link PiiSecuritySupport}; {@link #closeAndRestore()} rebinds the
 * test-profile ring that every cached {@code @SpringBootTest} context expects.
 */
@Testcontainers
class PiiKeyRotationIT {

	@Container
	static final PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:16"));

	/** The test-profile key ({@code src/test/resources/application.properties}), i.e. ring entry k1. */
	private static final byte[] K1_AES = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
	private static final byte[] K1_HMAC = "abcdef0123456789abcdef0123456789".getBytes(StandardCharsets.UTF_8);
	private static final String K2_AES_BASE64 = Base64.getEncoder()
			.encodeToString("rotation-k2-encryption-key-01234".getBytes(StandardCharsets.UTF_8));
	private static final String K2_HMAC_BASE64 = Base64.getEncoder()
			.encodeToString("rotation-k2-hmac-key-0123456789a".getBytes(StandardCharsets.UTF_8));

	private static final String X_NIK = "3171012501900011";
	private static final String X_PHONE = "08120000011";
	private static final String Y_NIK = "3171012501900022";
	private static final String Y_PHONE = "08120000022";
	private static final String Z_NIK = "3171012501900033";
	private static final String Z_PHONE = "08120000033";

	private static ConfigurableApplicationContext rotated;
	private static UUID customerX;

	@BeforeAll
	static void writeUnderK1ThenRotateToK2() {
		try (ConfigurableApplicationContext original = start()) {
			ContractCommandService commands = original.getBean(ContractCommandService.class);
			customerX = createContract(commands, X_NIK, X_PHONE, "B1001AA").customer().id();
			createContract(commands, Z_NIK, Z_PHONE, "B1003AA");
			// A row written before the key ring: a v1 envelope, hashed under k1 (the only key bound now).
			original.getBean(JdbcTemplate.class).update("""
					insert into customer (full_name, nik, nik_hash, phone, phone_lookup, address, created_at, updated_at)
					values ('Legacy Yanti', ?, ?, ?, ?, 'Bandung', now(), now())""",
					LegacyV1Envelope.seal(K1_AES, Y_NIK), PiiHasher.hashNik(Y_NIK),
					LegacyV1Envelope.seal(K1_AES, Y_PHONE), PiiHasher.hashPhone(Y_PHONE));
		}
		rotated = start(
				"serfira.security.pii.keys.k2.encryption-key=" + K2_AES_BASE64,
				"serfira.security.pii.keys.k2.hmac-key=" + K2_HMAC_BASE64,
				"serfira.security.pii.active-kid=k2");
	}

	@AfterAll
	static void closeAndRestore() {
		if (rotated != null) {
			rotated.close();
		}
		PiiSecuritySupport.configure(K1_AES, K1_HMAC);
	}

	private static ConfigurableApplicationContext start(String... extraProperties) {
		String[] base = {
				"spring.datasource.url=" + postgres.getJdbcUrl(),
				"spring.datasource.username=" + postgres.getUsername(),
				"spring.datasource.password=" + postgres.getPassword(),
				"server.port=0"};
		String[] properties = new String[base.length + extraProperties.length];
		System.arraycopy(base, 0, properties, 0, base.length);
		System.arraycopy(extraProperties, 0, properties, base.length, extraProperties.length);
		return new SpringApplicationBuilder(SerfiraCoreApplication.class).properties(properties).run();
	}

	private static com.serfira.contract.api.ContractResponse createContract(ContractCommandService commands,
			String nik, String phone, String plateNo) {
		return commands.create("rotation-it-" + UUID.randomUUID(), new CreateContractRequest(
				new CreateCustomerRequest("Customer " + nik.substring(12), nik, phone, "Jakarta"),
				new CreateAssetRequest(AssetType.MOTORCYCLE, "Honda", "Beat", null, plateNo),
				new BigDecimal("20000000.00"), new BigDecimal("4000000.00"), 12, InterestScheme.FLAT,
				new BigDecimal("0.0150"), LocalDate.of(2026, 12, 1)));
	}

	private static JdbcTemplate jdbc() {
		return rotated.getBean(JdbcTemplate.class);
	}

	private static long customerCount() {
		return jdbc().queryForObject("select count(*) from customer", Long.class);
	}

	@Test
	void customersWrittenUnderK1AreFoundByNikAndPhone() {
		CustomerSearchService search = rotated.getBean(CustomerSearchService.class);

		assertThat(search.findByRawNik(X_NIK)).get().extracting(summary -> summary.id()).isEqualTo(customerX);
		assertThat(search.findByRawPhone(X_PHONE)).get().extracting(summary -> summary.id()).isEqualTo(customerX);
		assertThat(search.findByRawNik(Y_NIK)).get().extracting(summary -> summary.fullName())
				.isEqualTo("Legacy Yanti");
		assertThat(search.findByRawPhone(Y_PHONE)).get().extracting(summary -> summary.fullName())
				.isEqualTo("Legacy Yanti");
	}

	@Test
	void contractCreateReusesTheK1Customer() {
		long before = customerCount();

		UUID reused = createContract(rotated.getBean(ContractCommandService.class), X_NIK, X_PHONE, "B2001BB")
				.customer().id();

		assertThat(reused).isEqualTo(customerX);
		assertThat(customerCount()).isEqualTo(before);
	}

	@Test
	void aDifferentPhoneForAK1NikIsStillAConflict() {
		assertThatThrownBy(() -> createContract(rotated.getBean(ContractCommandService.class), Y_NIK,
				"08129999999", "B2002BB"))
				.isInstanceOf(ConflictException.class)
				.hasMessage("NIK is already registered with a different phone number");
	}

	@Test
	void aK1PhoneUnderANewNikIsStillAConflict() {
		assertThatThrownBy(() -> createContract(rotated.getBean(ContractCommandService.class), "3171012501900099",
				X_PHONE, "B2003BB"))
				.isInstanceOf(ConflictException.class)
				.hasMessage("Phone number is already registered to a different customer");
	}

	@Test
	void newCustomersAreWrittenAsV2UnderK2() {
		String nik = "3171012501900044";
		createContract(rotated.getBean(ContractCommandService.class), nik, "08120000044", "B2004BB");

		var row = jdbc().queryForMap("select nik, phone, nik_hash from customer where nik_hash = ?",
				PiiHasher.nikCandidates(nik).get(0));
		assertThat(row.get("nik").toString()).startsWith("v2:k2:");
		assertThat(row.get("phone").toString()).startsWith("v2:k2:");
	}

	@Test
	void aCustomerMatchedUnderTwoKidsFailsLoudly() {
		// Corruption the candidate checks prevent: Z again, hashed under k2 (the k1 row is already there).
		jdbc().update("""
				insert into customer (full_name, nik, nik_hash, phone, phone_lookup, address, created_at, updated_at)
				values ('Duplicate Z', ?, ?, ?, ?, null, now(), now())""",
				PiiSecuritySupport.encrypt(Z_NIK), PiiHasher.nikCandidates(Z_NIK).get(0),
				PiiSecuritySupport.encrypt("08120000055"), PiiHasher.phoneCandidates("08120000055").get(0));
		try {
			assertThatThrownBy(() -> rotated.getBean(CustomerSearchService.class).findByRawNik(Z_NIK))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("2 rows");
		} finally {
			jdbc().update("delete from customer where full_name = 'Duplicate Z'");
		}
	}
}
