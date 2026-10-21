package com.serfira.shared.idempotency;

import com.serfira.TestcontainersConfiguration;
import com.serfira.contract.api.ContractResponse;
import com.serfira.contract.api.CreateAssetRequest;
import com.serfira.contract.api.CreateContractRequest;
import com.serfira.contract.api.CreateCustomerRequest;
import com.serfira.contract.application.ContractCommandService;
import com.serfira.contract.domain.AssetType;
import com.serfira.contract.domain.InterestScheme;
import com.serfira.shared.audit.AuditContext;
import com.serfira.shared.clock.Clock;
import com.serfira.shared.clock.FixedClock;
import com.serfira.shared.security.AppRole;
import com.serfira.support.TestJwts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * F4 / T18 — the idempotency-key cleanup hygiene job end to end against PostgreSQL and Flyway V1–V21.
 *
 * <p>The job physically deletes {@code idempotency_keys} rows whose retention window has passed
 * (relative to the injected {@link Clock}, Asia/Jakarta), writes exactly one {@code job_run} row named
 * {@code idempotency-cleanup} (ADR-013 A-9), and leaves live rows untouched. Deleting a spent claim row
 * must never re-open the key: the permanent business-row backstops ({@code uq_contract_idempotency}, the
 * V18 all-status {@code uq_payment_idempotency}) keep it spent (ADR-017 2026-10-11 resolution,
 * ADR-020 D3), so a later reuse is still rejected with a truthful 409 — this suite asserts the rejection
 * and the unchanged row count, not a specific error code.
 *
 * <p>Real commits (no test transaction), {@code FixedClock}, MockMvc + {@link TestJwts} for the HTTP
 * cases — mirroring {@code PaymentVoidIT} / {@code DailyJobRobustnessIT}. The cron is {@code -} (disabled),
 * so the job is invoked explicitly through the locked entry point.
 */
@Import({TestcontainersConfiguration.class, IdempotencyCleanupIT.FixedClockConfig.class})
@SpringBootTest
@AutoConfigureMockMvc
class IdempotencyCleanupIT {

	private static final UUID SYSTEM_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 2, 28);
	private static final String ADMIN = AppRole.ADMIN_OPERASIONAL.name();
	private static final String PERIOD_TOTAL = "1573333.33";

	@Autowired
	LockedIdempotencyCleanupJob job;

	@Autowired
	MockMvc mockMvc;

	@Autowired
	ContractCommandService contracts;

	@Autowired
	ObjectMapper objectMapper;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	Clock clock;

	private UUID actorId;
	private String adminToken;

	@BeforeEach
	void resetState() {
		truncateDomainTables();
		jdbc.update("delete from app_user where id <> ?", SYSTEM_USER_ID);
		actorId = jdbc.queryForObject("""
				insert into app_user (username, password_hash, full_name, role, is_active, created_at, updated_at)
					values (?, 'test-hash', 'Cleanup IT Actor', 'ADMIN_OPERASIONAL', TRUE,
						clock_timestamp(), clock_timestamp())
					returning id""", UUID.class, "cleanup-it-actor-" + UUID.randomUUID());
		adminToken = TestJwts.forRoles(actorId, ADMIN);
		fixedClock().setDate(BUSINESS_DATE);
	}

	@AfterEach
	void leaveCleanSharedDatabase() {
		truncateDomainTables();
	}

	@Test
	void theJobDeletesExpiredRowsAndKeepsLiveRows() {
		// Two rows that already expired relative to the FixedClock, and two that are still live.
		seedClaim("expired-a", clock.now().minusDays(1));
		seedClaim("expired-b", clock.now().minusSeconds(1));
		seedClaim("live-a", clock.now().plusSeconds(1));
		seedClaim("live-b", clock.now().plusDays(1));

		job.run();

		assertThat(claimExists("expired-a")).isFalse();
		assertThat(claimExists("expired-b")).isFalse();
		assertThat(claimExists("live-a")).isTrue();
		assertThat(claimExists("live-b")).isTrue();
	}

	@Test
	void theJobWritesOneCompletedJobRunWithTheDeletedCountAndNoFailures() {
		seedClaim("expired-a", clock.now().minusDays(2));
		seedClaim("expired-b", clock.now().minusDays(1));
		seedClaim("live-a", clock.now().plusDays(1));

		job.run();

		// Exactly one job_run row for this job name, COMPLETED, records_processed = rows deleted (2),
		// records_failed = 0, finished_at set (A-9 granularity and status rules).
		assertThat(jobRunCount()).isEqualTo(1L);
		assertThat(jdbc.queryForObject("""
				select count(*) from job_run
				where job_name = 'idempotency-cleanup' and status = 'COMPLETED'
					and records_processed = 2 and records_failed = 0 and finished_at is not null
				""", Long.class)).isEqualTo(1L);
		// The business date on the row is the Jakarta business date from the injected clock.
		assertThat(jdbc.queryForObject("select business_date from job_run where job_name = 'idempotency-cleanup'",
				LocalDate.class)).isEqualTo(BUSINESS_DATE);
	}

	@Test
	void aSpentContractKeyCannotMintASecondContractAfterItsClaimRowIsDeleted() throws Exception {
		MvcResult created = createContract("spent-contract-key");
		assertThat(created.getResponse().getStatus()).isEqualTo(201);
		assertThat(count("contract")).isEqualTo(1L);

		// The T18 cleanup physically removes the claim row once its retention window has passed. Force that
		// here by expiring the row and running the real job, then prove the key stays spent.
		expireContractClaim("spent-contract-key");
		job.run();
		assertThat(claimExists("spent-contract-key")).isFalse();

		// Reusing the spent key — even for a genuinely different party — must not mint a second contract.
		// Any truthful 409 is acceptable (DUPLICATE_CONTRACT / CONTRACT_STATE_INVALID /
		// IDEMPOTENCY_KEY_EXPIRED); assert the status and the unchanged count, not a specific code.
		MvcResult reused = createContract("spent-contract-key");
		assertThat(reused.getResponse().getStatus()).isEqualTo(409);
		assertThat(count("contract")).isEqualTo(1L);
	}

	@Test
	void aSpentPaymentKeyCannotCreateASecondPaymentAfterItsClaimRowIsDeleted() throws Exception {
		UUID contractId = activatedContract();
		MvcResult paid = postPayment(contractId, "spent-payment-key", PERIOD_TOTAL);
		assertThat(paid.getResponse().getStatus()).isEqualTo(201);
		assertThat(count("payment")).isEqualTo(1L);

		expirePaymentClaim("spent-payment-key");
		job.run();
		assertThat(claimExists("spent-payment-key")).isFalse();

		MvcResult reused = postPayment(contractId, "spent-payment-key", PERIOD_TOTAL);
		assertThat(reused.getResponse().getStatus()).isEqualTo(409);
		assertThat(count("payment")).isEqualTo(1L);
	}

	@Test
	void aVoidedPaymentsKeyStaysSpentAfterCleanup() throws Exception {
		UUID contractId = activatedContract();
		MvcResult paid = postPayment(contractId, "voided-payment-key", PERIOD_TOTAL);
		assertThat(paid.getResponse().getStatus()).isEqualTo(201);
		UUID paymentId = UUID.fromString(data(paid).get("id").asText());

		// Void the payment, then delete its creation claim row as the T18 job would once it expired.
		MvcResult voided = mockMvc.perform(post("/api/v1/payments/" + paymentId + "/void")
						.header("Authorization", "Bearer " + adminToken)
						.header("Idempotency-Key", "void-voided-payment-key")
						.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andReturn();
		assertThat(voided.getResponse().getStatus()).isEqualTo(200);

		expirePaymentClaim("voided-payment-key");
		job.run();
		assertThat(claimExists("voided-payment-key")).isFalse();

		// ADR-020 D3: the all-status uq_payment_idempotency — not the claim row — keeps the key spent, so a
		// reuse cannot create a second payment against the voided payment's key.
		MvcResult reused = postPayment(contractId, "voided-payment-key", PERIOD_TOTAL);
		assertThat(reused.getResponse().getStatus()).isEqualTo(409);
		assertThat(count("payment")).isEqualTo(1L);
	}

	// --- helpers ---------------------------------------------------------------------------------------

	private void seedClaim(String key, java.time.OffsetDateTime expiresAt) {
		jdbc.update("""
				insert into idempotency_keys
					(id, key, endpoint, request_hash, status, expires_at, created_at, created_by, updated_at, updated_by)
				values (gen_random_uuid(), ?, 'POST /api/v1/cleanup-probe', null, 'COMPLETED', ?,
					clock_timestamp(), ?, clock_timestamp(), ?)
				""", key, expiresAt, SYSTEM_USER_ID, SYSTEM_USER_ID);
	}

	private boolean claimExists(String key) {
		Long count = jdbc.queryForObject("select count(*) from idempotency_keys where key = ?", Long.class, key);
		return count != null && count > 0L;
	}

	/** Pushes the claim row for a contract-create key into the past so the cleanup job deletes it. */
	private void expireContractClaim(String key) {
		int updated = jdbc.update("""
				update idempotency_keys set expires_at = ?, updated_at = clock_timestamp()
				where endpoint = 'POST /api/v1/contracts' and key = ?
				""", clock.now().minusDays(1), key);
		assertThat(updated).isEqualTo(1);
	}

	/** Pushes the claim row for a payment-create key into the past so the cleanup job deletes it. */
	private void expirePaymentClaim(String key) {
		int updated = jdbc.update("""
				update idempotency_keys set expires_at = ?, updated_at = clock_timestamp()
				where endpoint = 'POST /api/v1/payments' and key = ?
				""", clock.now().minusDays(1), key);
		assertThat(updated).isEqualTo(1);
	}

	private MvcResult createContract(String idempotencyKey) throws Exception {
		return mockMvc.perform(post("/api/v1/contracts")
						.header("Authorization", "Bearer " + adminToken)
						.header("Idempotency-Key", idempotencyKey)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"customer":{"full_name":"Budi Santoso","nik":"3171012501900001",
									"phone":"08123456789","address":"Jakarta"},
								"asset":{"asset_type":"MOTORCYCLE","brand":"Honda","model":"Beat",
									"serial_no":null,"plate_no":"B1234XY"},
								"asset_price":20000000.00,"down_payment":4000000.00,"tenor_months":12,
								"interest_scheme":"FLAT","interest_rate":0.0150,
								"planned_start_date":"2026-01-31"}"""))
				.andReturn();
	}

	private UUID activatedContract() {
		ContractResponse draft = contracts.create("cleanup-it-contract-" + UUID.randomUUID(),
				new CreateContractRequest(
						new CreateCustomerRequest("Budi Santoso", "3171012501900001", "08123456789", "Jakarta"),
						new CreateAssetRequest(AssetType.MOTORCYCLE, "Honda", "Beat", null, "B1234XY"),
						new BigDecimal("20000000.00"), new BigDecimal("4000000.00"), 12, InterestScheme.FLAT,
						new BigDecimal("0.0150"), LocalDate.of(2026, 1, 31)));
		contracts.activate(draft.id(), null);
		return draft.id();
	}

	private MvcResult postPayment(UUID contractId, String idempotencyKey, String amount) throws Exception {
		return mockMvc.perform(post("/api/v1/payments")
						.header("Authorization", "Bearer " + adminToken)
						.header("Idempotency-Key", idempotencyKey)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"contract_id\":\"" + contractId + "\",\"amount\":" + amount
								+ ",\"channel\":\"CASH\"}"))
				.andReturn();
	}

	private JsonNode data(MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
	}

	private long jobRunCount() {
		return jdbc.queryForObject("select count(*) from job_run where job_name = 'idempotency-cleanup'",
				Long.class);
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
