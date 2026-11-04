package com.serfira.payment;

import com.serfira.TestcontainersConfiguration;
import com.serfira.contract.api.ContractResponse;
import com.serfira.contract.api.CreateAssetRequest;
import com.serfira.contract.api.CreateContractRequest;
import com.serfira.contract.api.CreateCustomerRequest;
import com.serfira.contract.application.ContractCommandService;
import com.serfira.contract.domain.AssetType;
import com.serfira.contract.domain.InterestScheme;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * T31 — the void idempotency key is single-use forever, even after the T18 cleanup job deletes the
 * {@code idempotency_keys} claim row (ADR-017 option A, ADR-020 "Deferred finding #5", resolved here).
 *
 * <p>A payment-<i>creation</i> key is kept spent forever by V18's all-status {@code uq_payment_idempotency}.
 * A <i>void</i> key had no such durable backstop — it lived only in the {@link
 * com.serfira.shared.idempotency.IdempotencyService} claim row. Once T18 deletes an expired claim, the same
 * key could be reused to void a <b>different</b> payment. V22 adds a durable, uniquely-constrained,
 * endpoint-scoped void-key → voided-payment association written inside the void transaction, so the reuse is
 * rejected before any work is done, and an identical replay of the original void still returns the stored
 * response once the claim row is reconstructed-from-nothing (i.e. gone, so the durable row answers).
 *
 * <p>Real commits (MockMvc, no test transaction), so the V22 unique index and the V3/V12 deferred invariant
 * triggers fire exactly where they do in production. Fixture mirrors {@code PaymentVoidIT}: the B5 default
 * terms, business date pinned so period 1 is due.
 */
@Import({TestcontainersConfiguration.class, PaymentClockTestConfiguration.class})
@SpringBootTest
@AutoConfigureMockMvc
class VoidIdempotencyKeyDurabilityIT {

	private static final UUID SYSTEM_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final LocalDate BUSINESS_DATE = PaymentClockTestConfiguration.BUSINESS_DATE;

	private static final String VOID_ENDPOINT = "POST /api/v1/payments/{id}/void";
	private static final String ADMIN = AppRole.ADMIN_OPERASIONAL.name();

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	ObjectMapper objectMapper;

	@Autowired
	Clock clock;

	@Autowired
	ContractCommandService contracts;

	private UUID actorId;
	private String adminToken;
	private UUID contractId;

	@BeforeEach
	void seedActivatedContractAndActor() {
		truncateDomainTables();
		jdbc.update("delete from app_user where id <> ?", SYSTEM_USER_ID);
		actorId = jdbc.queryForObject("""
				insert into app_user (username, password_hash, full_name, role, is_active, created_at, updated_at)
					values (?, 'test-hash', 'Void Durability IT Actor', 'ADMIN_OPERASIONAL', TRUE,
						clock_timestamp(), clock_timestamp())
					returning id""", UUID.class, "void-durability-actor-" + UUID.randomUUID());
		adminToken = TestJwts.forRoles(actorId, ADMIN);
		fixedClock().setDate(BUSINESS_DATE);

		ContractResponse draft = contracts.create("void-durability-contract-" + UUID.randomUUID(),
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
	void aReusedVoidKeyForADifferentPaymentIsRejectedAfterTheClaimRowIsCleanedUp() throws Exception {
		UUID firstPaymentId = postedPayment("t31-p1", "500000.00");
		UUID secondPaymentId = postedPayment("t31-p2", "500000.00");
		String sharedVoidKey = "t31-shared-void-key";

		MvcResult firstVoid = voidPayment(firstPaymentId, sharedVoidKey, "{}");
		assertThat(firstVoid.getResponse().getStatus()).isEqualTo(200);

		// Simulate the T18 cleanup job deleting the expired claim row: the IdempotencyService backstop is
		// gone, leaving only the durable V22 void-key association as the single-use-forever authority.
		jdbc.update("delete from idempotency_keys where endpoint = ? and key = ?", VOID_ENDPOINT, sharedVoidKey);
		assertThat(idempotencyClaimCount(sharedVoidKey)).isZero();

		long journalBefore = count("journal_entry");

		// Reusing the spent void key for a DIFFERENT payment must be rejected (409) and write nothing.
		MvcResult conflicting = voidPayment(secondPaymentId, sharedVoidKey, "{}");

		assertThat(conflicting.getResponse().getStatus()).isEqualTo(409);
		assertThat(errorCode(conflicting)).isEqualTo("IDEMPOTENCY_KEY_EXPIRED");
		assertThat(statusOf(secondPaymentId)).isEqualTo("POSTED");
		assertThat(count("journal_entry")).isEqualTo(journalBefore);
		assertThat(jdbc.queryForObject(
				"select count(*) from journal_entry where ref_id = ? and reversal_of_id is not null",
				Long.class, secondPaymentId)).isZero();
	}

	@Test
	void anIdenticalVoidReplayStillReturnsTheStoredResponseAfterTheClaimRowIsCleanedUp() throws Exception {
		UUID paymentId = postedPayment("t31-replay", "500000.00");
		String voidKey = "t31-replay-void-key";

		MvcResult firstVoid = voidPayment(paymentId, voidKey, "{}");
		assertThat(firstVoid.getResponse().getStatus()).isEqualTo(200);
		JsonNode firstData = data(firstVoid);
		assertThat(firstData.get("status").asText()).isEqualTo("VOIDED");

		// The claim row is gone (as T18 would delete it); the durable association now carries the key.
		jdbc.update("delete from idempotency_keys where endpoint = ? and key = ?", VOID_ENDPOINT, voidKey);
		assertThat(idempotencyClaimCount(voidKey)).isZero();

		long journalBefore = count("journal_entry");

		// An identical retry of the ORIGINAL void (same key, same payment) must still succeed and must not
		// post a second reversal — the durable row recognizes the key already voided this very payment.
		MvcResult replay = voidPayment(paymentId, voidKey, "{}");

		assertThat(replay.getResponse().getStatus()).isEqualTo(200);
		assertThat(data(replay).get("status").asText()).isEqualTo("VOIDED");
		assertThat(statusOf(paymentId)).isEqualTo("VOIDED");
		assertThat(count("journal_entry")).isEqualTo(journalBefore);
		assertThat(jdbc.queryForObject(
				"select count(*) from journal_entry where ref_id = ? and reversal_of_id is not null",
				Long.class, paymentId)).isEqualTo(1L);
	}

	// --- helpers ---------------------------------------------------------------------------------------

	private UUID postedPayment(String idempotencyKey, String amount) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/v1/payments")
						.header("Authorization", "Bearer " + adminToken)
						.header("Idempotency-Key", idempotencyKey)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"contract_id\":\"" + contractId + "\",\"amount\":" + amount
								+ ",\"channel\":\"CASH\"}"))
				.andReturn();
		assertThat(result.getResponse().getStatus()).isEqualTo(201);
		return UUID.fromString(data(result).get("id").asText());
	}

	private MvcResult voidPayment(UUID paymentId, String idempotencyKey, String body) throws Exception {
		return mockMvc.perform(post("/api/v1/payments/" + paymentId + "/void")
						.header("Authorization", "Bearer " + adminToken)
						.header("Idempotency-Key", idempotencyKey)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andReturn();
	}

	private long idempotencyClaimCount(String key) {
		return jdbc.queryForObject("select count(*) from idempotency_keys where endpoint = ? and key = ?",
				Long.class, VOID_ENDPOINT, key);
	}

	private String statusOf(UUID paymentId) {
		return jdbc.queryForObject("select status from payment where id = ?", String.class, paymentId);
	}

	private JsonNode data(MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
	}

	private String errorCode(MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("error").get("code").asText();
	}

	private long count(String table) {
		return jdbc.queryForObject("select count(*) from " + table, Long.class);
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
					payment_void_idempotency,
					settlement_quote, settlement, installment, journal_line, journal_entry,
					contract, asset, customer
				CASCADE""");
	}
}
