package com.serfira.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.serfira.SerfiraCoreApplication;
import com.serfira.contract.api.ContractResponse;
import com.serfira.contract.api.CreateAssetRequest;
import com.serfira.contract.api.CreateContractRequest;
import com.serfira.contract.api.CreateCustomerRequest;
import com.serfira.contract.application.ContractCommandService;
import com.serfira.contract.application.CustomerSearchService;
import com.serfira.contract.application.CustomerSummary;
import com.serfira.contract.application.PiiRekeyJob;
import com.serfira.contract.application.PiiRekeyMode;
import com.serfira.contract.application.PiiRekeyOutcome;
import com.serfira.contract.application.PiiRekeyPreflightException;
import com.serfira.contract.application.PiiRekeyVerificationException;
import com.serfira.contract.application.PiiRetirementReport.KeyVerdict;
import com.serfira.contract.application.PiiRetirementReport.Verdict;
import com.serfira.contract.domain.AssetType;
import com.serfira.contract.domain.InterestScheme;
import com.serfira.contract.infrastructure.CustomerRepository;
import com.serfira.shared.audit.AuditContext;
import com.serfira.shared.security.LegacyV1Envelope;
import com.serfira.shared.security.PiiHasher;
import com.serfira.shared.security.PiiSecuritySupport;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.LoggerFactory;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * T28b acceptance: the re-keying pass on a real database. Context A writes six customers under {@code k1} — four
 * through the create flow ({@code v2:k1}) and two pre-ring {@code v1:} rows. Context B rotates to {@code k2} with the
 * runner enabled: its startup dry run writes nothing, a duplicate aborts the pre-flight with zero writes, a failed
 * verification rolls back its page, a rerun resumes and moves every remaining row, and the report says when {@code
 * k1} can be retired. Context C then runs on {@code k2} alone and still reads, finds and reuses every customer.
 *
 * <p>Every context rebinds the JVM-global {@link PiiSecuritySupport}; {@link #detachCloseAndRestore()} rebinds the
 * test-profile ring that every cached {@code @SpringBootTest} context expects.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PiiRekeyIT {

	@Container
	static final PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:16"));

	/** The test-profile key ({@code src/test/resources/application.properties}), i.e. ring entry k1. */
	private static final byte[] K1_AES = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
	private static final byte[] K1_HMAC = "abcdef0123456789abcdef0123456789".getBytes(StandardCharsets.UTF_8);
	private static final String K2_AES_BASE64 = Base64.getEncoder()
			.encodeToString("rotation-k2-encryption-key-01234".getBytes(StandardCharsets.UTF_8));
	private static final String K2_HMAC_BASE64 = Base64.getEncoder()
			.encodeToString("rotation-k2-hmac-key-0123456789a".getBytes(StandardCharsets.UTF_8));

	/** Customers created through the application (v2:k1); X is the first. */
	private static final List<Seed> CREATED = List.of(
			new Seed("3171012501900101", "08120000101"),
			new Seed("3171012501900102", "08120000102"),
			new Seed("3171012501900103", "08120000103"),
			new Seed("3171012501900104", "08120000104"));
	/** Rows written before the key ring: v1 envelopes, hashed under k1. */
	private static final List<Seed> LEGACY = List.of(
			new Seed("3171012501900105", "08120000105"),
			new Seed("3171012501900106", "08120000106"));
	private static final List<Seed> SEEDED = Stream.concat(CREATED.stream(), LEGACY.stream()).toList();
	private static final Seed X = CREATED.get(0);
	/** The phone of the duplicate inserted by test 2. */
	private static final String DUPLICATE_PHONE = "08120000199";

	private static final String SNAPSHOT_SQL = """
			select id, full_name, nik, nik_hash, phone, phone_lookup, address, created_at, created_by, updated_at,
			       updated_by
			  from customer""";

	private static JdbcTemplate jdbc;
	private static ConfigurableApplicationContext context;
	private static UUID customerX;
	private static Map<UUID, Map<String, Object>> initial;
	private static Map<UUID, Map<String, Object>> afterRekey;
	/** Every snapshot taken, so test 7 can look for any stored hash in the captured log. */
	private static final List<Map<UUID, Map<String, Object>>> snapshots = new ArrayList<>();
	private static Logger applicationLogger;
	private static ListAppender<ILoggingEvent> appender;

	record Seed(String nik, String phone) {
	}

	record Run(String status, int recordsProcessed, int recordsFailed) {
	}

	@BeforeAll
	static void seedUnderK1ThenRotateWithTheRunner() {
		jdbc = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
				postgres.getPassword()));
		// Context A: the runner is off, k1 is the only key, v2 writes are on (ADR-025 D7 done).
		try (ConfigurableApplicationContext original = start("serfira.security.pii.write-envelope=v2")) {
			assertThat(original.getBeanNamesForType(PiiRekeyJob.class)).isEmpty();
			ContractCommandService commands = original.getBean(ContractCommandService.class);
			for (int i = 0; i < CREATED.size(); i++) {
				Seed seed = CREATED.get(i);
				UUID id = createContract(commands, seed.nik(), seed.phone(), "B10" + i + "0AA").customer().id();
				if (i == 0) {
					customerX = id;
				}
			}
			JdbcTemplate originalJdbc = original.getBean(JdbcTemplate.class);
			for (Seed seed : LEGACY) {
				originalJdbc.update("""
						insert into customer (full_name, nik, nik_hash, phone, phone_lookup, address, created_at,
						                      updated_at)
						values ('Legacy ' || ?, ?, ?, ?, ?, 'Bandung', now(), now())""",
						seed.nik().substring(12), LegacyV1Envelope.seal(K1_AES, seed.nik()),
						PiiHasher.hashNik(seed.nik()), LegacyV1Envelope.seal(K1_AES, seed.phone()),
						PiiHasher.hashPhone(seed.phone()));
			}
		}
		initial = snapshot();
		assertThat(initial).hasSize(6);

		// Context B: k2 added and active, the runner enabled in its default (dry-run) mode, two rows per page.
		context = start(
				"serfira.security.pii.keys.k2.encryption-key=" + K2_AES_BASE64,
				"serfira.security.pii.keys.k2.hmac-key=" + K2_HMAC_BASE64,
				"serfira.security.pii.active-kid=k2",
				"serfira.security.pii.write-envelope=v2",
				"serfira.maintenance.pii-rekey.enabled=true",
				"serfira.maintenance.pii-rekey.batch-size=2");

		applicationLogger = (Logger) LoggerFactory.getLogger("com.serfira");
		appender = new ListAppender<>();
		appender.start();
		applicationLogger.addAppender(appender);
	}

	@AfterAll
	static void detachCloseAndRestore() {
		if (appender != null) {
			applicationLogger.detachAppender(appender);
			appender.stop();
		}
		if (context != null) {
			context.close();
		}
		PiiSecuritySupport.configure(K1_AES, K1_HMAC);
	}

	/**
	 * The datasource is a default property, like in {@code PiiKeyRotationIT}; the ring and runner settings are
	 * command-line arguments so that a blank value (context C's flat pair) overrides {@code application.properties}.
	 */
	private static ConfigurableApplicationContext start(String... extraProperties) {
		return new SpringApplicationBuilder(SerfiraCoreApplication.class)
				.properties(
						"spring.datasource.url=" + postgres.getJdbcUrl(),
						"spring.datasource.username=" + postgres.getUsername(),
						"spring.datasource.password=" + postgres.getPassword(),
						"server.port=0")
				.run(Arrays.stream(extraProperties).map(property -> "--" + property).toArray(String[]::new));
	}

	private static ContractResponse createContract(ContractCommandService commands, String nik, String phone,
			String plateNo) {
		return commands.create("rekey-it-" + UUID.randomUUID(), new CreateContractRequest(
				new CreateCustomerRequest("Customer " + nik.substring(12), nik, phone, "Jakarta"),
				new CreateAssetRequest(AssetType.MOTORCYCLE, "Honda", "Beat", null, plateNo),
				new BigDecimal("20000000.00"), new BigDecimal("4000000.00"), 12, InterestScheme.FLAT,
				new BigDecimal("0.0150"), LocalDate.of(2026, 12, 1)));
	}

	private static Map<UUID, Map<String, Object>> snapshot() {
		Map<UUID, Map<String, Object>> rows = new LinkedHashMap<>();
		for (Map<String, Object> row : jdbc.queryForList(SNAPSHOT_SQL)) {
			rows.put((UUID) row.get("id"), row);
		}
		snapshots.add(rows);
		return rows;
	}

	private static Map<UUID, Map<String, Object>> snapshotOf(Set<UUID> ids) {
		Map<UUID, Map<String, Object>> rows = new LinkedHashMap<>(snapshot());
		rows.keySet().retainAll(ids);
		return rows;
	}

	private static Run latestRun() {
		return jdbc.queryForObject("""
				select status, records_processed, records_failed
				  from job_run
				 where job_name = 'pii-rekey'
				 order by started_at desc
				 limit 1""", (rs, rowNum) -> new Run(rs.getString(1), rs.getInt(2), rs.getInt(3)));
	}

	private static PiiRekeyJob job() {
		return context.getBean(PiiRekeyJob.class);
	}

	private static KeyVerdict verdict(PiiRekeyOutcome outcome, String kid) {
		return outcome.report().verdictFor(kid).orElseThrow();
	}

	private static String text(Map<String, Object> row, String column) {
		return row.get(column).toString();
	}

	@Test
	@Order(1)
	void theStartupDryRunWritesNothing() {
		assertThat(latestRun()).isEqualTo(new Run("COMPLETED", 0, 0));
		assertThat(snapshot()).isEqualTo(initial);

		PiiRekeyOutcome outcome = job().run(PiiRekeyMode.DRY_RUN);

		assertThat(outcome.census().rowsToRekey()).isEqualTo(6);
		assertThat(outcome.census().v1Rows()).isEqualTo(2);
		assertThat(verdict(outcome, "k1").verdict()).isEqualTo(Verdict.NOT_RETIRABLE);
		assertThat(verdict(outcome, "k1").envelopeRows()).isEqualTo(6);
		assertThat(verdict(outcome, "k2").verdict()).isEqualTo(Verdict.ACTIVE);
		assertThat(snapshot()).isEqualTo(initial);
	}

	@Test
	@Order(2)
	void aDuplicateAbortsThePreFlightWithZeroWrites() {
		// X again, sealed and hashed under k2: the unique index cannot see it, only a decrypting census can.
		jdbc.update("""
				insert into customer (full_name, nik, nik_hash, phone, phone_lookup, address, created_at, updated_at)
				values ('Duplicate X', ?, ?, ?, ?, null, now(), now())""",
				PiiSecuritySupport.encrypt(X.nik()), PiiHasher.nikCandidates(X.nik()).get(0),
				PiiSecuritySupport.encrypt(DUPLICATE_PHONE), PiiHasher.phoneCandidates(DUPLICATE_PHONE).get(0));
		try {
			// A dry run tells the operator in advance: it completes, writes nothing and counts the duplicate.
			PiiRekeyOutcome dryRun = job().run(PiiRekeyMode.DRY_RUN);

			assertThat(dryRun.census().duplicateNikRows()).isEqualTo(1);
			assertThat(snapshotOf(initial.keySet())).isEqualTo(initial);
			assertThat(latestRun()).isEqualTo(new Run("COMPLETED", 0, 0));

			assertThatThrownBy(() -> job().run(PiiRekeyMode.EXECUTE))
					.isInstanceOf(PiiRekeyPreflightException.class)
					.hasMessageContaining("1 duplicate NIK");

			assertThat(snapshotOf(initial.keySet())).isEqualTo(initial);
			assertThat(latestRun()).isEqualTo(new Run("FAILED", 0, 1));
		} finally {
			jdbc.update("delete from customer where full_name = 'Duplicate X'");
		}
	}

	@Test
	@Order(3)
	void aVerificationFailureRollsBackItsPageAndAborts() {
		List<UUID> ids = jdbc.queryForList("select id from customer order by id", UUID.class);
		assertThat(ids).hasSize(6);
		UUID target = ids.get(3);
		jdbc.execute("""
				create function pii_rekey_it_corrupt_lookup() returns trigger language plpgsql as $$
				begin
				    if NEW.id = '%s' then
				        NEW.phone_lookup := repeat('0', 64);
				    end if;
				    return NEW;
				end $$""".formatted(target));
		jdbc.execute("""
				create trigger pii_rekey_it_corrupt_lookup before update on customer
				    for each row execute function pii_rekey_it_corrupt_lookup()""");
		try {
			assertThatThrownBy(() -> job().run(PiiRekeyMode.EXECUTE))
					.isInstanceOf(PiiRekeyVerificationException.class)
					.hasMessageContaining(target.toString())
					.hasMessageContaining("phone_lookup");

			Map<UUID, Map<String, Object>> now = snapshot();
			for (UUID committed : ids.subList(0, 2)) {
				assertThat(text(now.get(committed), "nik")).startsWith("v2:k2:");
				assertThat(text(now.get(committed), "phone")).startsWith("v2:k2:");
			}
			for (UUID rolledBack : ids.subList(2, 4)) {
				assertThat(now.get(rolledBack)).isEqualTo(initial.get(rolledBack));
			}
			assertThat(latestRun()).isEqualTo(new Run("FAILED", 2, 1));
		} finally {
			jdbc.execute("drop trigger if exists pii_rekey_it_corrupt_lookup on customer");
			jdbc.execute("drop function if exists pii_rekey_it_corrupt_lookup()");
		}
	}

	@Test
	@Order(4)
	void aRerunResumesAndMovesEveryRemainingRow() {
		// Page 1 is already on k2; the cursor must advance past it by the rows scanned, not the rows rewritten.
		PiiRekeyOutcome outcome = job().run(PiiRekeyMode.EXECUTE);

		assertThat(outcome.rekeyedRows()).isEqualTo(4);
		Map<UUID, Map<String, Object>> now = snapshot();
		assertThat(now.keySet()).isEqualTo(initial.keySet());
		Set<Seed> decrypted = new HashSet<>();
		for (Map.Entry<UUID, Map<String, Object>> entry : now.entrySet()) {
			Map<String, Object> row = entry.getValue();
			Map<String, Object> before = initial.get(entry.getKey());
			assertThat(text(row, "nik")).startsWith("v2:k2:");
			assertThat(text(row, "phone")).startsWith("v2:k2:");
			String nik = PiiSecuritySupport.decrypt(text(row, "nik"));
			String phone = PiiSecuritySupport.decrypt(text(row, "phone"));
			decrypted.add(new Seed(nik, phone));
			assertThat(text(row, "nik_hash")).isEqualTo(PiiHasher.hashNik(nik));
			assertThat(text(row, "phone_lookup")).isEqualTo(PiiHasher.hashPhone(phone));
			assertThat(row.get("updated_by")).isEqualTo(AuditContext.SYSTEM_USER_ID);
			for (String unchanged : List.of("full_name", "address", "created_at", "created_by")) {
				assertThat(row.get(unchanged)).as(unchanged).isEqualTo(before.get(unchanged));
			}
		}
		assertThat(decrypted).containsExactlyInAnyOrderElementsOf(SEEDED);
		assertThat(latestRun()).isEqualTo(new Run("COMPLETED", 4, 0));
		afterRekey = now;
	}

	@Test
	@Order(5)
	void aSecondRunChangesNothing() {
		PiiRekeyOutcome outcome = job().run(PiiRekeyMode.EXECUTE);

		assertThat(outcome.rekeyedRows()).isZero();
		assertThat(snapshot()).isEqualTo(afterRekey);
	}

	@Test
	@Order(6)
	void theReportFollowsTheRetentionRule() {
		PiiRekeyOutcome outcome = job().run(PiiRekeyMode.DRY_RUN);

		assertThat(outcome.census().rowsOffActiveHashes()).isZero();
		KeyVerdict k1 = verdict(outcome, "k1");
		assertThat(k1.envelopeRows()).isZero();
		// Context A's four create claims still need k1 to recompute their fingerprints on a replay.
		assertThat(k1.unexpiredFingerprints()).isEqualTo(4);
		assertThat(k1.verdict()).isEqualTo(Verdict.NOT_RETIRABLE);

		jdbc.update("delete from idempotency_keys");

		assertThat(verdict(job().run(PiiRekeyMode.DRY_RUN), "k1").verdict()).isEqualTo(Verdict.RETIRABLE);
	}

	@Test
	@Order(7)
	void noLogLineCarriesANikAPhoneOrAFullHash() {
		List<String> forbidden = new ArrayList<>();
		for (Seed seed : SEEDED) {
			forbidden.addAll(List.of(seed.nik(), PiiHasher.normalizeNik(seed.nik()), seed.phone(),
					PiiHasher.normalizePhone(seed.phone())));
		}
		forbidden.addAll(List.of(DUPLICATE_PHONE, PiiHasher.normalizePhone(DUPLICATE_PHONE)));
		for (Map<UUID, Map<String, Object>> taken : snapshots) {
			for (Map<String, Object> row : taken.values()) {
				for (String column : List.of("nik", "nik_hash", "phone", "phone_lookup")) {
					forbidden.add(text(row, column));
				}
			}
		}
		List<String> messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();

		// The guard must have seen the report, or it proves nothing.
		assertThat(messages).anyMatch(message -> message.startsWith("pii-rekey key kid="));
		assertThat(messages).anyMatch(message -> message.startsWith("pii-rekey census scannedRows="));
		for (String message : messages) {
			for (String value : forbidden) {
				assertThat(message).doesNotContain(value);
			}
		}
	}

	@Test
	@Order(8)
	void aRingWithoutK1ReadsFindsAndReusesEveryCustomer() {
		context.close();
		context = null;
		context = start(
				"serfira.security.pii.encryption-key=",
				"serfira.security.pii.hmac-key=",
				"serfira.security.pii.keys.k2.encryption-key=" + K2_AES_BASE64,
				"serfira.security.pii.keys.k2.hmac-key=" + K2_HMAC_BASE64,
				"serfira.security.pii.active-kid=k2",
				"serfira.security.pii.write-envelope=v2");
		assertThat(PiiSecuritySupport.ringKids()).containsExactly("k2");

		CustomerSearchService search = context.getBean(CustomerSearchService.class);
		for (Seed seed : SEEDED) {
			UUID byNik = search.findByRawNik(seed.nik()).map(CustomerSummary::id).orElseThrow();
			UUID byPhone = search.findByRawPhone(seed.phone()).map(CustomerSummary::id).orElseThrow();
			assertThat(byPhone).isEqualTo(byNik);
		}
		assertThat(context.getBean(CustomerRepository.class).findAll())
				.extracting(customer -> new Seed(customer.getNik(), customer.getPhone()))
				.containsExactlyInAnyOrderElementsOf(SEEDED);

		long before = jdbc.queryForObject("select count(*) from customer", Long.class);
		UUID reused = createContract(context.getBean(ContractCommandService.class), X.nik(), X.phone(), "B9008CC")
				.customer().id();

		assertThat(reused).isEqualTo(customerX);
		assertThat(jdbc.queryForObject("select count(*) from customer", Long.class)).isEqualTo(before);
	}
}
