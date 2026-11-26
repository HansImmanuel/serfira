package com.serfira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.serfira.contract.api.ContractResponse;
import com.serfira.contract.api.CreateAssetRequest;
import com.serfira.contract.api.CreateContractRequest;
import com.serfira.contract.api.CreateCustomerRequest;
import com.serfira.contract.application.ContractCommandService;
import com.serfira.contract.application.PiiRekeyJob;
import com.serfira.contract.application.PiiRekeyMode;
import com.serfira.contract.application.PiiRetirementReport.KeyVerdict;
import com.serfira.contract.application.PiiRetirementReport.Verdict;
import com.serfira.contract.domain.AssetType;
import com.serfira.contract.domain.InterestScheme;
import com.serfira.payment.api.PaymentRequest;
import com.serfira.payment.api.PaymentResponse;
import com.serfira.payment.application.PaymentApplicationService;
import com.serfira.payment.domain.PaymentChannel;
import com.serfira.shared.clock.Clock;
import com.serfira.shared.clock.FixedClock;
import com.serfira.shared.config.SystemParameterKeys;
import com.serfira.shared.config.SystemParameterService;
import com.serfira.shared.error.ConflictException;
import com.serfira.shared.error.IdempotencyKeyExpiredException;
import com.serfira.shared.security.PiiSecuritySupport;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * T28b acceptance for idempotency across a real rotation. Context A (the test profile, {@code k1}) records a contract
 * create and a payment; the create claim is then made to look pre-T28b (NULL kid). Context B rotates to {@code k2}:
 * replays still return the stored response, a different body is still a conflict, new claims record {@code k2}, and
 * {@code k1} stays not retirable until its last fingerprint expires. Context C has no {@code k1}: a replay that needs
 * it fails loudly, and past expiry the replay is rejected before the key is needed.
 *
 * <p>Every context rebinds the JVM-global {@link PiiSecuritySupport}; {@link #restoreTestProfileRing()} rebinds the
 * test-profile ring that every cached {@code @SpringBootTest} context expects.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IdempotencyKeyRotationIT {

	@Container
	static final PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:16"));

	private static final byte[] K1_AES = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
	private static final byte[] K1_HMAC = "abcdef0123456789abcdef0123456789".getBytes(StandardCharsets.UTF_8);
	private static final String K2_AES_BASE64 = Base64.getEncoder()
			.encodeToString("rotation-k2-encryption-key-01234".getBytes(StandardCharsets.UTF_8));
	private static final String K2_HMAC_BASE64 = Base64.getEncoder()
			.encodeToString("rotation-k2-hmac-key-0123456789a".getBytes(StandardCharsets.UTF_8));
	private static final LocalDate START = LocalDate.of(2026, 12, 1);

	private static JdbcTemplate jdbc;
	private static ConfigurableApplicationContext context;
	private static UUID contractId;
	private static UUID paymentId;

	/** A fixed clock for every context started here, so claim expiry is deterministic. */
	@TestConfiguration(proxyBeanMethods = false)
	static class RotationClock {

		@Bean
		@Primary
		Clock rotationClock() {
			return new FixedClock(START);
		}
	}

	@BeforeAll
	static void claimUnderK1ThenRotateToK2() {
		jdbc = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
				postgres.getPassword()));
		// Context A: the test profile (k1, v1 writes).
		try (ConfigurableApplicationContext original = start()) {
			ContractCommandService commands = original.getBean(ContractCommandService.class);
			ContractResponse created = commands.create("rot-create", createRequest("B1234XY"));
			contractId = created.id();
			commands.activate(contractId, null);
			PaymentResponse paid = original.getBean(PaymentApplicationService.class)
					.create("rot-pay", paymentRequest("1000000.00"));
			paymentId = paid.id();

			assertThat(kidOf("rot-create")).isEqualTo("k1");
			assertThat(kidOf("rot-pay")).isEqualTo("k1");
			// A claim from before T28b carries no kid.
			jdbc.update("update idempotency_keys set request_hash_kid = null where key = 'rot-create'");
		}

		// Context B: k1 and k2, k2 active, the runner enabled (its startup dry run writes nothing).
		context = start(
				"serfira.security.pii.keys.k2.encryption-key=" + K2_AES_BASE64,
				"serfira.security.pii.keys.k2.hmac-key=" + K2_HMAC_BASE64,
				"serfira.security.pii.active-kid=k2",
				"serfira.security.pii.write-envelope=v2",
				"serfira.maintenance.pii-rekey.enabled=true");
	}

	@AfterAll
	static void restoreTestProfileRing() {
		if (context != null) {
			context.close();
		}
		PiiSecuritySupport.configure(K1_AES, K1_HMAC);
	}

	/**
	 * The datasource is a default property; the ring and runner settings are command-line arguments so that a blank
	 * value (context C's flat pair) overrides {@code application.properties}.
	 */
	private static ConfigurableApplicationContext start(String... extraProperties) {
		return new SpringApplicationBuilder(SerfiraCoreApplication.class, RotationClock.class)
				.properties(
						"spring.datasource.url=" + postgres.getJdbcUrl(),
						"spring.datasource.username=" + postgres.getUsername(),
						"spring.datasource.password=" + postgres.getPassword(),
						"server.port=0")
				.run(Arrays.stream(extraProperties).map(property -> "--" + property).toArray(String[]::new));
	}

	private static CreateContractRequest createRequest(String plateNo) {
		return new CreateContractRequest(
				new CreateCustomerRequest("Rotation Customer", "3171012501900201", "08120000201", "Jakarta"),
				new CreateAssetRequest(AssetType.MOTORCYCLE, "Honda", "Beat", null, plateNo),
				new BigDecimal("20000000.00"), new BigDecimal("4000000.00"), 12, InterestScheme.FLAT,
				new BigDecimal("0.0150"), START);
	}

	private static PaymentRequest paymentRequest(String amount) {
		return new PaymentRequest(contractId, new BigDecimal(amount), PaymentChannel.CASH);
	}

	private static String kidOf(String key) {
		return jdbc.queryForObject("select request_hash_kid from idempotency_keys where key = ?", String.class, key);
	}

	private static long count(String table) {
		return jdbc.queryForObject("select count(*) from " + table, Long.class);
	}

	private static FixedClock clock() {
		return (FixedClock) context.getBean(Clock.class);
	}

	private static Duration pastRetention() {
		int days = context.getBean(SystemParameterService.class)
				.requireInt(SystemParameterKeys.IDEMPOTENCY_KEY_RETENTION_DAYS);
		return Duration.ofDays(days).plusMinutes(1);
	}

	private static KeyVerdict k1Verdict(PiiRekeyMode mode) {
		return context.getBean(PiiRekeyJob.class).run(mode).report().verdictFor("k1").orElseThrow();
	}

	@Test
	@Order(1)
	void aContractCreateReplayAcrossTheRotationReturnsTheStoredResponse() {
		long contracts = count("contract");

		ContractResponse replay = context.getBean(ContractCommandService.class).create("rot-create",
				createRequest("B1234XY"));

		assertThat(replay.id()).isEqualTo(contractId);
		assertThat(count("contract")).isEqualTo(contracts);
	}

	@Test
	@Order(2)
	void aDifferentCreateBodyUnderTheLegacyClaimIsAConflict() {
		assertThatThrownBy(() -> context.getBean(ContractCommandService.class).create("rot-create",
				createRequest("B9999ZZ"))).isInstanceOf(ConflictException.class);
	}

	@Test
	@Order(3)
	void aPaymentReplayAcrossTheRotationReturnsTheStoredPayment() {
		long payments = count("payment");

		PaymentResponse replay = context.getBean(PaymentApplicationService.class).create("rot-pay",
				paymentRequest("1000000.00"));

		assertThat(replay.id()).isEqualTo(paymentId);
		assertThat(count("payment")).isEqualTo(payments);
	}

	@Test
	@Order(4)
	void aDifferentPaymentBodyIsAConflict() {
		assertThatThrownBy(() -> context.getBean(PaymentApplicationService.class).create("rot-pay",
				paymentRequest("2000000.00"))).isInstanceOf(ConflictException.class);
	}

	@Test
	@Order(5)
	void newClaimsRecordTheActiveKid() {
		context.getBean(PaymentApplicationService.class).create("rot-pay-2", paymentRequest("1000000.00"));

		assertThat(kidOf("rot-pay-2")).isEqualTo("k2");
	}

	@Test
	@Order(6)
	void k1StaysNotRetirableUntilItsLastFingerprintExpires() {
		context.getBean(PiiRekeyJob.class).run(PiiRekeyMode.EXECUTE);

		KeyVerdict before = k1Verdict(PiiRekeyMode.DRY_RUN);
		assertThat(before.verdict()).isEqualTo(Verdict.NOT_RETIRABLE);
		assertThat(before.unexpiredFingerprints()).isEqualTo(2);

		clock().advanceBy(pastRetention());

		assertThat(k1Verdict(PiiRekeyMode.DRY_RUN).verdict()).isEqualTo(Verdict.RETIRABLE);
	}

	@Test
	@Order(7)
	void aReplayNeedingARetiredKeyFailsLoudly() {
		context.close();
		context = start(
				"serfira.security.pii.encryption-key=",
				"serfira.security.pii.hmac-key=",
				"serfira.security.pii.keys.k2.encryption-key=" + K2_AES_BASE64,
				"serfira.security.pii.keys.k2.hmac-key=" + K2_HMAC_BASE64,
				"serfira.security.pii.active-kid=k2",
				"serfira.security.pii.write-envelope=v2");
		clock().setDate(START);
		long payments = count("payment");

		assertThatThrownBy(() -> context.getBean(PaymentApplicationService.class).create("rot-pay",
				paymentRequest("1000000.00")))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("k1");
		assertThat(count("payment")).isEqualTo(payments);
	}

	@Test
	@Order(8)
	void pastExpiryTheReplayIsRejectedBeforeTheKeyIsNeeded() {
		clock().advanceBy(pastRetention());

		assertThatThrownBy(() -> context.getBean(PaymentApplicationService.class).create("rot-pay",
				paymentRequest("1000000.00"))).isInstanceOf(IdempotencyKeyExpiredException.class);
	}
}
