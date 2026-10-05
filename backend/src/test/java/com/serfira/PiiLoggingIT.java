package com.serfira;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.serfira.penalty.application.LockedDailyServicingJob;
import com.serfira.shared.clock.Clock;
import com.serfira.shared.clock.FixedClock;
import com.serfira.shared.security.AppRole;
import com.serfira.support.TestJwts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * PII-at-rest-in-logs guard (04_GAPS_ADDENDUM.md §9, steering {@code 60-security}): plaintext PII must
 * never reach a log line. NIK and phone are encrypted at rest and masked in responses; this test proves the
 * same for logging by driving the three flows named in the task — contract create (+ activate), a payment,
 * and the daily servicing job — with a known-plaintext PII fixture, then asserting no captured log line
 * contains the raw NIK, the raw phone, or its normalized form.
 *
 * <p>A Logback {@link ListAppender} is attached to the application logger ({@code com.serfira}) at DEBUG for
 * the capture window and detached in {@code @AfterEach}, so it never leaks into other suites. The capture is
 * scoped to the application's own loggers deliberately: forcing the ROOT logger (and thus
 * {@code org.springframework.web}) to DEBUG would enable Spring MVC's request-body trace, which echoes the
 * raw request DTO — framework tracing that is never on at production log levels and is not application
 * logging. The guard this test enforces is that <b>our</b> code never writes plaintext PII to a log.
 */
@Import({TestcontainersConfiguration.class, PiiLoggingIT.FixedClockConfig.class})
@SpringBootTest
@AutoConfigureMockMvc
class PiiLoggingIT {

	private static final UUID SYSTEM_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

	/** First installment due date of the fixture contract. */
	private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 2, 28);
	/**
	 * Period 1 is paid in full on {@link #BUSINESS_DATE}, so for the job to actually accrue penalty (and emit
	 * the penalty-accrual log this guard must scan) it has to run on a date where period 2 is chargeable.
	 * Period 2 is due 2026-03-31; with the snapshotted 3-day grace its first chargeable day is 2026-04-04,
	 * where it is one day late. On that date the job bills period 2's interest and accrues one penalty day.
	 */
	private static final LocalDate LATE_BUSINESS_DATE = LocalDate.of(2026, 4, 4);

	/** The accrual logger whose "Accrued …" line this guard requires the job to have emitted. */
	private static final String PENALTY_ACCRUAL_LOGGER = "com.serfira.penalty.application.PenaltyAccrualService";

	/** Known-plaintext PII fixture. The raw phone normalizes (trunk 0 → 62) to 628123456789. */
	private static final String NIK = "3171012501900001";
	private static final String RAW_PHONE = "08123456789";
	private static final String NORMALIZED_PHONE = "628123456789";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	ObjectMapper objectMapper;

	@Autowired
	Clock clock;

	@Autowired
	LockedDailyServicingJob job;

	/** The application's root package logger: every logger under com.serfira propagates to this one. */
	private static final String APPLICATION_LOGGER = "com.serfira";

	private UUID actorId;
	private String token;
	private Logger applicationLogger;
	private ch.qos.logback.classic.Level originalLevel;
	private ListAppender<ILoggingEvent> appender;

	@BeforeEach
	void seedActorAndAttachAppender() {
		truncateDomainTables();
		jdbc.update("delete from app_user where id <> ?", SYSTEM_USER_ID);
		actorId = jdbc.queryForObject("""
				insert into app_user (username, password_hash, full_name, role, is_active, created_at, updated_at)
					values (?, 'test-hash', 'PII IT Actor', 'ADMIN_OPERASIONAL', TRUE,
						clock_timestamp(), clock_timestamp())
					returning id
				""", UUID.class, "pii-it-actor-" + UUID.randomUUID());
		token = TestJwts.forRoles(actorId, AppRole.ADMIN_OPERASIONAL.name());
		fixedClock().setDate(BUSINESS_DATE);

		applicationLogger = (Logger) LoggerFactory.getLogger(APPLICATION_LOGGER);
		originalLevel = applicationLogger.getLevel();
		applicationLogger.setLevel(ch.qos.logback.classic.Level.DEBUG);
		appender = new ListAppender<>();
		appender.start();
		applicationLogger.addAppender(appender);
	}

	@AfterEach
	void detachAppenderAndClean() {
		if (applicationLogger != null && appender != null) {
			applicationLogger.detachAppender(appender);
			appender.stop();
			applicationLogger.setLevel(originalLevel);
		}
		truncateDomainTables();
		jdbc.update("delete from app_user where id <> ?", SYSTEM_USER_ID);
	}

	@Test
	void noPlaintextPiiReachesTheLogsAcrossCreateActivatePaymentAndTheDailyJob() throws Exception {
		// (a) create + activate a contract with the known-plaintext customer.
		UUID contractId = activateFixtureContract();

		// (b) a payment on the due date (bills interest and resolves the installment).
		MvcResult paid = pay(contractId, "pii-payment", "1573333.33");
		assertThat(paid.getResponse().getStatus()).isEqualTo(201);

		// (c) the daily servicing job for a date where period 2 is chargeable (bills + accrues penalty, which
		// logs per contract). Period 1 is already paid, so this is the installment that produces the accrual.
		fixedClock().setDate(LATE_BUSINESS_DATE);
		job.run(LATE_BUSINESS_DATE);

		List<ILoggingEvent> events = new ArrayList<>(appender.list);
		// The penalty-accrual path must actually have run and logged, otherwise the PII scan below would
		// never cover it. Require the PenaltyAccrualService "Accrued …" line for this business date.
		assertThat(events)
				.as("the daily job must have emitted a penalty-accrual log for %s", LATE_BUSINESS_DATE)
				.anyMatch(event -> event.getLoggerName().equals(PENALTY_ACCRUAL_LOGGER)
						&& event.getFormattedMessage().startsWith("Accrued ")
						&& event.getFormattedMessage().contains(LATE_BUSINESS_DATE.toString()));
		for (ILoggingEvent event : events) {
			String line = renderFully(event);
			assertThat(line)
					.as("a log line leaked plaintext PII: %s", line)
					.doesNotContain(NIK)
					.doesNotContain(RAW_PHONE)
					.doesNotContain(NORMALIZED_PHONE);
		}
	}

	/** Formatted message plus every argument and any throwable message, so nothing escapes the scan. */
	private static String renderFully(ILoggingEvent event) {
		StringBuilder sb = new StringBuilder(event.getFormattedMessage());
		if (event.getArgumentArray() != null) {
			for (Object arg : event.getArgumentArray()) {
				sb.append('|').append(arg);
			}
		}
		if (event.getThrowableProxy() != null) {
			sb.append('|').append(event.getThrowableProxy().getMessage());
		}
		return sb.toString();
	}

	private UUID activateFixtureContract() throws Exception {
		String createBody = "{"
				+ "\"customer\":{\"full_name\":\"Budi Santoso\",\"nik\":\"" + NIK + "\",\"phone\":\"" + RAW_PHONE
				+ "\",\"address\":\"Jakarta\"},"
				+ "\"asset\":{\"asset_type\":\"MOTORCYCLE\",\"brand\":\"Honda\",\"model\":\"Beat\",\"plate_no\":\"B1234XY\"},"
				+ "\"asset_price\":20000000.00,\"down_payment\":4000000.00,\"tenor_months\":12,"
				+ "\"interest_scheme\":\"FLAT\",\"interest_rate\":0.0150,\"planned_start_date\":\"2026-01-31\"}";
		MvcResult created = mockMvc.perform(post("/api/v1/contracts")
				.header("Authorization", "Bearer " + token)
				.header("Idempotency-Key", "pii-create-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(createBody)).andReturn();
		assertThat(created.getResponse().getStatus()).isEqualTo(201);
		UUID contractId = UUID.fromString(data(created).get("id").asText());

		MvcResult activated = mockMvc.perform(post("/api/v1/contracts/" + contractId + "/activate")
				.header("Authorization", "Bearer " + token)).andReturn();
		assertThat(activated.getResponse().getStatus()).isEqualTo(200);
		return contractId;
	}

	private MvcResult pay(UUID contractId, String idempotencyKey, String amount) throws Exception {
		String body = "{\"contract_id\":\"" + contractId + "\",\"amount\":" + amount + ",\"channel\":\"CASH\"}";
		return mockMvc.perform(post("/api/v1/payments")
				.header("Authorization", "Bearer " + token)
				.header("Idempotency-Key", idempotencyKey)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body)).andReturn();
	}

	private JsonNode data(MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
	}

	private FixedClock fixedClock() {
		return (FixedClock) clock;
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

	@TestConfiguration(proxyBeanMethods = false)
	static class FixedClockConfig {
		@Bean
		@Primary
		Clock fixedClock() {
			return new FixedClock(BUSINESS_DATE);
		}
	}
}
