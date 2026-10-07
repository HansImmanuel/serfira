package com.serfira.shared.security;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.serfira.TestcontainersConfiguration;
import com.serfira.contract.api.ContractResponse;
import com.serfira.contract.api.CreateAssetRequest;
import com.serfira.contract.api.CreateContractRequest;
import com.serfira.contract.api.CreateCustomerRequest;
import com.serfira.contract.application.ContractCommandService;
import com.serfira.contract.domain.AssetType;
import com.serfira.contract.domain.InterestScheme;
import com.serfira.payment.api.PaymentRequest;
import com.serfira.payment.domain.PaymentChannel;
import com.serfira.support.TestJwts;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * The Addendum §3.4 endpoint-to-role matrix as enforced by the single matcher table (ADR-015 D3), cell by
 * cell, plus deny-by-default for everything nobody registered.
 *
 * <p>The matrix requests carry no fixtures: a malformed body or a random id. So an allowed cell answers
 * 200, 400 or 404 from the application, never 401/403, and a denied cell must be exactly 403
 * {@code FORBIDDEN}. A separate test sends fully valid write payloads with the denied roles to prove a
 * denial leaves no row behind.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class EndpointRoleMatrixIT {

	private static final UUID SYSTEM_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

	private static final String ADMIN = AppRole.ADMIN_OPERASIONAL.name();
	private static final String FINANCE = AppRole.FINANCE.name();
	private static final String MANAJEMEN = AppRole.MANAJEMEN.name();

	private static final String RANDOM_ID = "3f2c3b8e-7f0a-4c55-9a3c-5b1d2e4f6a7b";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	ObjectMapper objectMapper;

	@Autowired
	ContractCommandService contracts;

	private UUID actorId;

	@BeforeEach
	void resetAndSeedActor() {
		truncateDomainTables();
		jdbc.update("delete from app_user where id <> ?", SYSTEM_USER_ID);
		actorId = jdbc.queryForObject("""
				insert into app_user (username, password_hash, full_name, role, is_active, created_at, updated_at)
					values (?, 'test-hash', 'Matrix IT Actor', 'ADMIN_OPERASIONAL', TRUE,
						clock_timestamp(), clock_timestamp())
					returning id""", UUID.class, "matrix-it-actor-" + UUID.randomUUID());
	}

	@AfterEach
	void leaveCleanSharedDatabase() {
		truncateDomainTables();
	}

	/** One row per cell: method, path, role, allowed. Body-carrying POSTs send {@code {}}. */
	static Stream<Arguments> matrix() {
		List<Arguments> cells = new ArrayList<>();
		Map<String, List<String>> allowedRoles = new LinkedHashMap<>();
		allowedRoles.put("POST /api/v1/contracts", List.of(ADMIN));
		allowedRoles.put("POST /api/v1/contracts/" + RANDOM_ID + "/activate", List.of(ADMIN));
		allowedRoles.put("POST /api/v1/payments", List.of(ADMIN));
		allowedRoles.put("GET /api/v1/contracts", List.of(ADMIN, FINANCE, MANAJEMEN));
		allowedRoles.put("GET /api/v1/contracts/" + RANDOM_ID, List.of(ADMIN, FINANCE, MANAJEMEN));
		allowedRoles.put("GET /api/v1/contracts/" + RANDOM_ID + "/installments", List.of(ADMIN, FINANCE, MANAJEMEN));
		allowedRoles.put("GET /api/v1/reports/aging", List.of(ADMIN, FINANCE, MANAJEMEN));
		// Statement (T9): ADMIN_OPERASIONAL + FINANCE, MANAJEMEN denied (Addendum §3.4).
		allowedRoles.put("GET /api/v1/contracts/" + RANDOM_ID + "/statement", List.of(ADMIN, FINANCE));
		// Credit (T14): apply is ADMIN_OPERASIONAL only; read is ADMIN_OPERASIONAL + FINANCE (Addendum §3.4).
		allowedRoles.put("POST /api/v1/contracts/" + RANDOM_ID + "/credit/apply", List.of(ADMIN));
		allowedRoles.put("GET /api/v1/contracts/" + RANDOM_ID + "/credit", List.of(ADMIN, FINANCE));
		// Penalty waive/reduce (T15): ADMIN_OPERASIONAL only (Addendum §3.4, ADR-019 D2).
		allowedRoles.put("POST /api/v1/penalty-adjustments", List.of(ADMIN));
		// Not registered: denied for every role (C-5 deferred, no payment reads).
		allowedRoles.put("PUT /api/v1/contracts/" + RANDOM_ID, List.of());
		allowedRoles.put("DELETE /api/v1/contracts/" + RANDOM_ID, List.of());
		allowedRoles.put("GET /api/v1/payments", List.of());
		allowedRoles.put("GET /api/v1/no-such-route", List.of());
		allowedRoles.put("GET /error", List.of());
		for (Map.Entry<String, List<String>> endpoint : allowedRoles.entrySet()) {
			String[] methodAndPath = endpoint.getKey().split(" ", 2);
			for (String role : List.of(ADMIN, FINANCE, MANAJEMEN)) {
				cells.add(Arguments.of(methodAndPath[0], methodAndPath[1], role, endpoint.getValue().contains(role)));
			}
		}
		return cells.stream();
	}

	@ParameterizedTest(name = "{2} {0} {1} -> allowed={3}")
	@MethodSource("matrix")
	void eachCellAnswersAsTheMatrixSays(String method, String path, String role, boolean allowed) throws Exception {
		MockHttpServletRequestBuilder request = request(HttpMethod.valueOf(method), path)
				.header("Authorization", "Bearer " + TestJwts.forRoles(actorId, role));
		if (!method.equals("GET") && !method.equals("DELETE")) {
			request = request.contentType(MediaType.APPLICATION_JSON).content("{}");
		}

		MvcResult result = mockMvc.perform(request).andReturn();
		int status = result.getResponse().getStatus();

		if (allowed) {
			assertThat(status)
					.as("allowed cell answered %s: %s", status, result.getResponse().getContentAsString())
					.isNotIn(401, 403);
		} else {
			assertThat(status).isEqualTo(403);
			assertThat(errorCode(result)).isEqualTo("FORBIDDEN");
		}
	}

	@Test
	void unauthenticatedRequestToUnlistedPathIsUnauthorizedNotForbidden() throws Exception {
		MvcResult result = mockMvc.perform(request(HttpMethod.GET, "/api/v1/no-such-route")).andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(401);
		assertThat(errorCode(result)).isEqualTo("UNAUTHORIZED");
	}

	@Test
	void deniedWritesWithValidPayloadsStoreNothing() throws Exception {
		ContractResponse active = contracts.create("matrix-active-" + UUID.randomUUID(), contractRequest("B1111AA"));
		contracts.activate(active.id(), null);
		ContractResponse draft = contracts.create("matrix-draft-" + UUID.randomUUID(), contractRequest("B2222BB"));
		Map<String, Long> before = rowCounts();

		for (String role : List.of(FINANCE, MANAJEMEN)) {
			String bearer = "Bearer " + TestJwts.forRoles(actorId, role);
			MvcResult payment = mockMvc.perform(post("/api/v1/payments")
							.header("Authorization", bearer)
							.header("Idempotency-Key", "denied-payment-" + role)
							.contentType(MediaType.APPLICATION_JSON)
							.content(objectMapper.writeValueAsString(
									new PaymentRequest(active.id(), new BigDecimal("1573333.33"), PaymentChannel.CASH))))
					.andReturn();
			MvcResult create = mockMvc.perform(post("/api/v1/contracts")
							.header("Authorization", bearer)
							.header("Idempotency-Key", "denied-contract-" + role)
							.contentType(MediaType.APPLICATION_JSON)
							.content(objectMapper.writeValueAsString(contractRequest("B3333CC"))))
					.andReturn();
			MvcResult activate = mockMvc.perform(post("/api/v1/contracts/" + draft.id() + "/activate")
							.header("Authorization", bearer))
					.andReturn();
			MvcResult waiver = mockMvc.perform(post("/api/v1/penalty-adjustments")
							.header("Authorization", bearer)
							.contentType(MediaType.APPLICATION_JSON)
							.content(objectMapper.writeValueAsString(penaltyAdjustmentRequest(active.id()))))
					.andReturn();

			for (MvcResult denied : List.of(payment, create, activate, waiver)) {
				assertThat(denied.getResponse().getStatus()).isEqualTo(403);
				assertThat(errorCode(denied)).isEqualTo("FORBIDDEN");
			}
		}

		assertThat(rowCounts()).isEqualTo(before);
		assertThat(jdbc.queryForObject("select status from contract where id = ?", String.class, draft.id()))
				.isEqualTo("DRAFT");
	}

	private Map<String, Long> rowCounts() {
		Map<String, Long> counts = new LinkedHashMap<>();
		for (String table : List.of("contract", "installment", "payment", "payment_allocation",
				"idempotency_keys", "journal_entry", "penalty_accrual", "penalty_adjustment")) {
			counts.put(table, jdbc.queryForObject("select count(*) from " + table, Long.class));
		}
		return counts;
	}

	private String errorCode(MvcResult result) throws Exception {
		JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
		return body.get("error").get("code").asText();
	}

	/** A fully valid penalty-adjustment body for the given contract; the denial fires before validation. */
	private Map<String, Object> penaltyAdjustmentRequest(UUID contractId) {
		UUID installmentId = jdbc.queryForObject(
				"select id from installment where contract_id = ? and period_no = 1", UUID.class, contractId);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("contract_id", contractId.toString());
		body.put("installment_id", installmentId.toString());
		body.put("adjustment_type", "WAIVE");
		body.put("amount", new BigDecimal("1000.00"));
		body.put("reason", "denied role should write nothing");
		return body;
	}

	private static CreateContractRequest contractRequest(String plateNo) {
		return new CreateContractRequest(
				new CreateCustomerRequest("Budi Santoso", "3171012501900001", "08123456789", "Jakarta"),
				new CreateAssetRequest(AssetType.MOTORCYCLE, "Honda", "Beat", null, plateNo),
				new BigDecimal("20000000.00"), new BigDecimal("4000000.00"), 12, InterestScheme.FLAT,
				new BigDecimal("0.0150"), LocalDate.of(2026, 1, 31));
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
}
