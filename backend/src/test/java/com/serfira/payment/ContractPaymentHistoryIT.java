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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * {@code GET /api/v1/contracts/{id}/payments} end to end (task T39, plan Decisions 1-4): every payment of a
 * contract, POSTED and VOIDED, newest first, with its allocations, excess and void details.
 *
 * <p>Real commits (MockMvc, no test transaction), as in {@link PaymentVoidIT}, whose fixture this copies:
 * 20,000,000 asset, 4,000,000 down payment, 12 x FLAT 1.5%; period total 1,573,333.33. The fixed business
 * date pins period 1 due, so every payment shares one {@code paid_at} and {@code payment_no desc} decides
 * the order.
 */
@Import({TestcontainersConfiguration.class, PaymentClockTestConfiguration.class})
@SpringBootTest
@AutoConfigureMockMvc
class ContractPaymentHistoryIT {

	private static final UUID SYSTEM_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final LocalDate BUSINESS_DATE = PaymentClockTestConfiguration.BUSINESS_DATE;

	private static final String PERIOD_TOTAL = "1573333.33";

	private static final String ADMIN = AppRole.ADMIN_OPERASIONAL.name();
	private static final String FINANCE = AppRole.FINANCE.name();
	private static final String MANAJEMEN = AppRole.MANAJEMEN.name();

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

	private String adminToken;
	private String financeToken;
	private String manajemenToken;
	private UUID contractId;

	@BeforeEach
	void seedActivatedContractAndActors() {
		truncateDomainTables();
		jdbc.update("delete from app_user where id <> ?", SYSTEM_USER_ID);
		adminToken = TestJwts.forRoles(seedUser(ADMIN), ADMIN);
		financeToken = TestJwts.forRoles(seedUser(FINANCE), FINANCE);
		manajemenToken = TestJwts.forRoles(seedUser(MANAJEMEN), MANAJEMEN);
		fixedClock().setDate(BUSINESS_DATE);

		ContractResponse draft = contracts.create("history-it-contract-" + UUID.randomUUID(),
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
	void listsEveryPaymentNewestFirstWithAllocationsExcessAndVoidFields() throws Exception {
		UUID paymentA = postedPayment("history-a", PERIOD_TOTAL);
		voidPayment(paymentA, "history-void-a", "{\"reason\":\"operator error\"}");
		UUID paymentB = postedPayment("history-b", "1623333.33");

		MvcResult result = mockMvc.perform(get("/api/v1/contracts/" + contractId + "/payments")
						.header("Authorization", "Bearer " + adminToken))
				.andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		JsonNode payments = data(result);
		assertThat(payments).hasSize(2);

		JsonNode b = payments.get(0);
		assertThat(b.get("id").asText()).isEqualTo(paymentB.toString());
		assertThat(b.get("status").asText()).isEqualTo("POSTED");
		assertThat(b.get("amount").asText()).isEqualTo("1623333.33");
		assertThat(b.get("excess_amount").asText()).isEqualTo("50000.00");
		assertThat(b.get("voided_at").isNull()).isTrue();
		assertThat(b.get("void_reason").isNull()).isTrue();
		JsonNode excess = null;
		for (JsonNode allocation : b.get("allocations")) {
			if ("EXCESS".equals(allocation.get("allocation_type").asText())) {
				excess = allocation;
			}
		}
		assertThat(excess).isNotNull();
		assertThat(excess.get("installment_id").isNull()).isTrue();
		assertThat(excess.get("amount").asText()).isEqualTo("50000.00");

		JsonNode a = payments.get(1);
		assertThat(a.get("id").asText()).isEqualTo(paymentA.toString());
		assertThat(a.get("status").asText()).isEqualTo("VOIDED");
		assertThat(a.get("amount").asText()).isEqualTo(PERIOD_TOTAL);
		assertThat(a.get("voided_at").isNull()).isFalse();
		assertThat(a.get("void_reason").asText()).isEqualTo("operator error");
		assertThat(a.get("excess_amount").asText()).isEqualTo("0.00");
		assertThat(a.get("allocations").size()).isGreaterThan(0);
	}

	@Test
	void aContractWithoutPaymentsAnswersAnEmptyList() throws Exception {
		MvcResult result = mockMvc.perform(get("/api/v1/contracts/" + contractId + "/payments")
						.header("Authorization", "Bearer " + adminToken))
				.andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		assertThat(data(result).isArray()).isTrue();
		assertThat(data(result)).isEmpty();
	}

	@Test
	void anUnknownContractIsNotFound() throws Exception {
		MvcResult result = mockMvc.perform(get("/api/v1/contracts/" + UUID.randomUUID() + "/payments")
						.header("Authorization", "Bearer " + adminToken))
				.andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(404);
		assertThat(errorCode(result)).isEqualTo("CONTRACT_NOT_FOUND");
	}

	@Test
	void financeReadsButManajemenIsForbidden() throws Exception {
		MvcResult finance = mockMvc.perform(get("/api/v1/contracts/" + contractId + "/payments")
						.header("Authorization", "Bearer " + financeToken))
				.andReturn();
		MvcResult manajemen = mockMvc.perform(get("/api/v1/contracts/" + contractId + "/payments")
						.header("Authorization", "Bearer " + manajemenToken))
				.andReturn();

		assertThat(finance.getResponse().getStatus()).isEqualTo(200);
		assertThat(manajemen.getResponse().getStatus()).isEqualTo(403);
		assertThat(errorCode(manajemen)).isEqualTo("FORBIDDEN");
	}

	// --- helpers ---------------------------------------------------------------------------------------

	private UUID seedUser(String role) {
		return jdbc.queryForObject("""
				insert into app_user (username, password_hash, full_name, role, is_active, created_at, updated_at)
					values (?, 'test-hash', 'History IT Actor', ?, TRUE, clock_timestamp(), clock_timestamp())
					returning id""", UUID.class, "hist-" + role.substring(0, 3).toLowerCase() + "-" + UUID.randomUUID(), role);
	}

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

	private void voidPayment(UUID paymentId, String idempotencyKey, String body) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/v1/payments/" + paymentId + "/void")
						.header("Authorization", "Bearer " + adminToken)
						.header("Idempotency-Key", idempotencyKey)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andReturn();
		assertThat(result.getResponse().getStatus()).isEqualTo(200);
	}

	private JsonNode data(MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
	}

	private String errorCode(MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("error").get("code").asText();
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
}
