package com.serfira.shared.observability;

import com.serfira.TestcontainersConfiguration;
import com.serfira.contract.api.ContractResponse;
import com.serfira.contract.api.CreateAssetRequest;
import com.serfira.contract.api.CreateContractRequest;
import com.serfira.contract.api.CreateCustomerRequest;
import com.serfira.contract.application.ContractCommandService;
import com.serfira.contract.domain.AssetType;
import com.serfira.contract.domain.InterestScheme;
import com.serfira.payment.PaymentClockTestConfiguration;
import com.serfira.shared.clock.Clock;
import com.serfira.shared.clock.FixedClock;
import com.serfira.shared.security.AppRole;
import com.serfira.support.TestJwts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * T20 / Addendum §10 — the correlation-id filter end to end against a real PostgreSQL 16 and the real
 * security chain: every response carries {@code X-Request-Id}, a client-supplied id is echoed, an absent or
 * unusable one is replaced with a fresh UUID, and the id of the request that first claims an
 * {@code idempotency_keys} row is persisted so a later replay is traceable to it (X-3).
 *
 * <p>The payment fixture mirrors {@code PaymentApiIT} (same seed recipe, same fixed business date): an
 * activated contract whose first installment is due, paid through {@code POST /api/v1/payments} — the
 * retry-safe write path the correlation id exists to trace. Requests are real commits (MockMvc, no test
 * transaction).
 */
@Import({TestcontainersConfiguration.class, PaymentClockTestConfiguration.class})
@SpringBootTest
@AutoConfigureMockMvc
class CorrelationIdFilterIT {

	private static final UUID SYSTEM_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final String PAYMENTS_ENDPOINT = "POST /api/v1/payments";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	Clock clock;

	@Autowired
	ContractCommandService contracts;

	private UUID actorId;
	private String token;
	private UUID contractId;

	@BeforeEach
	void seedActivatedContractAndActor() {
		truncateDomainTables();
		jdbc.update("delete from app_user where id <> ?", SYSTEM_USER_ID);
		actorId = jdbc.queryForObject("""
				insert into app_user (username, password_hash, full_name, role, is_active, created_at, updated_at)
					values (?, 'test-hash', 'Observability IT Actor', 'ADMIN_OPERASIONAL', TRUE,
						clock_timestamp(), clock_timestamp())
					returning id
				""", UUID.class, "observability-it-actor-" + UUID.randomUUID());
		token = TestJwts.forRoles(actorId, AppRole.ADMIN_OPERASIONAL.name());
		fixedClock().setDate(PaymentClockTestConfiguration.BUSINESS_DATE);

		ContractResponse draft = contracts.create("observability-it-contract-" + UUID.randomUUID(),
				new CreateContractRequest(
						new CreateCustomerRequest("Budi Santoso", "3171012501900001", "08123456789", "Jakarta"),
						new CreateAssetRequest(AssetType.MOTORCYCLE, "Honda", "Beat", null, "B1234XY"),
						new BigDecimal("20000000.00"), new BigDecimal("4000000.00"), 12, InterestScheme.FLAT,
						new BigDecimal("0.0150"), LocalDate.of(2026, 1, 31)));
		contracts.activate(draft.id(), null);
		contractId = draft.id();
	}

	@AfterEach
	void leaveCleanSharedDatabase() {
		truncateDomainTables();
	}

	@Test
	void everyResponseCarriesARequestIdEvenWhenTheRequestSuppliesNone() throws Exception {
		MvcResult result = mockMvc.perform(get("/actuator/health")).andReturn();

		String requestId = result.getResponse().getHeader(CorrelationId.HEADER);
		assertThat(requestId).isNotBlank();
		assertUuid(requestId);
	}

	@Test
	void aClientSuppliedRequestIdIsEchoedBack() throws Exception {
		MvcResult result = mockMvc.perform(get("/actuator/health")
				.header(CorrelationId.HEADER, "upstream-trace-42")).andReturn();

		assertThat(result.getResponse().getHeader(CorrelationId.HEADER)).isEqualTo("upstream-trace-42");
	}

	@Test
	void aBlankOrOverLongSuppliedIdIsReplacedWithAFreshUuid() throws Exception {
		String overLong = "x".repeat(CorrelationId.MAX_LENGTH + 1);

		MvcResult blank = mockMvc.perform(get("/actuator/health")
				.header(CorrelationId.HEADER, "   ")).andReturn();
		MvcResult tooLong = mockMvc.perform(get("/actuator/health")
				.header(CorrelationId.HEADER, overLong)).andReturn();

		for (MvcResult result : List.of(blank, tooLong)) {
			String requestId = result.getResponse().getHeader(CorrelationId.HEADER);
			assertThat(requestId).isNotBlank().isNotEqualTo(overLong);
			assertUuid(requestId);
		}
	}

	@Test
	void anUnauthenticatedRequestStillCarriesARequestId() throws Exception {
		// The filter is ordered before Spring Security, so even a request the security chain rejects is
		// correlated — authentication failures are traceable too.
		MvcResult result = mockMvc.perform(post("/api/v1/payments")
				.header("Idempotency-Key", "unauthenticated-observability")
				.header(CorrelationId.HEADER, "unauthenticated-request-id")
				.contentType(MediaType.APPLICATION_JSON)
				.content(paymentBody("100000.00"))).andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(401);
		assertThat(result.getResponse().getHeader(CorrelationId.HEADER)).isEqualTo("unauthenticated-request-id");
		assertThat(countPaymentClaims()).isZero();
	}

	@Test
	void aClaimedIdempotencyKeyStoresTheOriginatingRequestId() throws Exception {
		MvcResult result = postPayment("observability-claim", "claim-origin-request-id", "100000.00");

		assertThat(result.getResponse().getStatus()).isEqualTo(201);
		assertThat(result.getResponse().getHeader(CorrelationId.HEADER)).isEqualTo("claim-origin-request-id");
		assertThat(storedRequestId()).isEqualTo("claim-origin-request-id");
	}

	@Test
	void aReplayedRetryIsTraceableToTheFirstRequestId() throws Exception {
		MvcResult first = postPayment("observability-replay", "originating-request-id", "100000.00");
		assertThat(first.getResponse().getStatus()).isEqualTo(201);

		// A retry on a different request (new correlation id) must replay, never re-execute (TS §2.5).
		MvcResult replay = postPayment("observability-replay", "retry-request-id", "100000.00");

		assertThat(replay.getResponse().getStatus()).isEqualTo(201);
		assertThat(replay.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
		// The replay's response header echoes the retry's own id, while the claim row keeps the origin's id —
		// the retry is traceable back to the request that created the resource (X-3).
		assertThat(replay.getResponse().getHeader(CorrelationId.HEADER)).isEqualTo("retry-request-id");
		assertThat(jdbc.queryForObject("select count(*) from idempotency_keys where endpoint = ?",
				Long.class, PAYMENTS_ENDPOINT)).isEqualTo(1L);
		assertThat(storedRequestId()).isEqualTo("originating-request-id");
	}

	private MvcResult postPayment(String idempotencyKey, String requestId, String amount) throws Exception {
		return mockMvc.perform(post("/api/v1/payments")
				.header("Authorization", "Bearer " + token)
				.header("Idempotency-Key", idempotencyKey)
				.header(CorrelationId.HEADER, requestId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(paymentBody(amount))).andReturn();
	}

	private String paymentBody(String amount) {
		return "{\"contract_id\":\"" + contractId + "\",\"amount\":" + amount + ",\"channel\":\"CASH\"}";
	}

	private String storedRequestId() {
		return jdbc.queryForObject("select request_id from idempotency_keys where endpoint = ?",
				String.class, PAYMENTS_ENDPOINT);
	}

	private long countPaymentClaims() {
		return jdbc.queryForObject("select count(*) from idempotency_keys where endpoint = ?",
				Long.class, PAYMENTS_ENDPOINT);
	}

	private static void assertUuid(String value) {
		assertThatCode(() -> UUID.fromString(value))
				.as("generated request id must be a UUID, got '%s'", value)
				.doesNotThrowAnyException();
	}

	private FixedClock fixedClock() {
		return (FixedClock) clock;
	}

	private void truncateDomainTables() {
		// Shared Testcontainers hygiene (same recipe as the other ITs): TRUNCATE does not fire the
		// row-level/deferred triggers, and app_user keeps the seeded SYSTEM row.
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
}
