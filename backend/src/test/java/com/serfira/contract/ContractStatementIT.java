package com.serfira.contract;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * T9 — the contract statement (rekening koran) end to end over HTTP against PostgreSQL (PRD C-5, FE §2.7,
 * ADR-013 A-8): chronological ledger-literal rows, the {@code ref_type} filter, inclusive business-zone
 * date-range boundaries, the role matrix (ADMIN_OPERASIONAL/FINANCE; MANAJEMEN → 403), an unknown contract
 * → 404, and a DRAFT contract → an empty statement.
 *
 * <p>The clock is fixed to {@link #TODAY}. A contract is created and activated through the real command
 * service — activation posts the CONTRACT_ACTIVATION disbursement entry (PIUTANG_POKOK debit / KAS credit)
 * dated at the start date — and a payment is driven over HTTP, posting a PAYMENT entry dated TODAY. So the
 * statement spans two well-separated business dates, which the range test leans on.
 */
@Import({TestcontainersConfiguration.class, ContractStatementIT.FixedClockConfig.class})
@SpringBootTest
@AutoConfigureMockMvc
class ContractStatementIT {

	private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);
	private static final LocalDate START_DATE = LocalDate.of(2026, 1, 31);
	private static final UUID SYSTEM_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

	@Autowired
	MockMvc mockMvc;

	@Autowired
	ContractCommandService commands;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	ObjectMapper objectMapper;

	@Autowired
	Clock clock;

	private UUID actorId;
	private String token;

	@BeforeEach
	void resetAndSeedActor() {
		truncateDomainTables();
		((FixedClock) clock).setDate(TODAY);
		jdbc.update("delete from app_user where id <> ?", SYSTEM_USER_ID);
		actorId = jdbc.queryForObject("""
				insert into app_user (username, password_hash, full_name, role, is_active, created_at, updated_at)
					values (?, 'test-hash', 'Statement IT Actor', 'ADMIN_OPERASIONAL', TRUE,
						clock_timestamp(), clock_timestamp())
					returning id""", UUID.class, "statement-it-actor-" + UUID.randomUUID());
		token = TestJwts.forRoles(actorId, AppRole.ADMIN_OPERASIONAL.name());
	}

	@AfterEach
	void leaveCleanSharedDatabase() {
		truncateDomainTables();
	}

	@Test
	void listsEveryJournalLineOnceInChronologicalOrderWithLiteralAmounts() throws Exception {
		UUID contractId = activateContract("B1234XY", "3171012501900001", "08123456789");
		BigDecimal principal = jdbc.queryForObject(
				"select principal from contract where id = ?", BigDecimal.class, contractId);
		pay(contractId, "500000.00");

		JsonNode content = content(getStatement(contractId, ""));

		// Activation posts 2 lines (PIUTANG_POKOK debit + KAS credit) dated at the start date; the payment
		// posts its own lines dated TODAY. Activation comes first chronologically.
		assertThat(content.size()).isGreaterThanOrEqualTo(3);

		JsonNode first = content.get(0);
		assertThat(first.get("ref_type").asText()).isEqualTo("CONTRACT_ACTIVATION");
		assertThat(first.get("entry_date").asText()).startsWith("2026-01-31");
		assertThat(first.get("is_reversal").asBoolean()).isFalse();
		assertThat(first.get("ref_id").asText()).isEqualTo(contractId.toString());

		// The two activation lines are literal: one PIUTANG_POKOK debit = principal, one KAS credit = principal.
		List<JsonNode> activationLines = new ArrayList<>();
		content.forEach(row -> {
			if (row.get("ref_type").asText().equals("CONTRACT_ACTIVATION")) {
				activationLines.add(row);
			}
		});
		assertThat(activationLines).hasSize(2);
		JsonNode piutang = activationLines.stream()
				.filter(row -> row.get("account_code").asText().equals("PIUTANG_POKOK")).findFirst().orElseThrow();
		JsonNode kas = activationLines.stream()
				.filter(row -> row.get("account_code").asText().equals("KAS")).findFirst().orElseThrow();
		assertThat(piutang.get("debit").decimalValue()).isEqualByComparingTo(principal);
		assertThat(piutang.get("credit").decimalValue()).isEqualByComparingTo("0.00");
		assertThat(piutang.get("account_name").asText()).isEqualTo("Receivable — principal");
		assertThat(kas.get("credit").decimalValue()).isEqualByComparingTo(principal);
		assertThat(kas.get("debit").decimalValue()).isEqualByComparingTo("0.00");

		// A PAYMENT entry exists, dated TODAY, after the activation entry (chronological order).
		List<String> refTypesInOrder = new ArrayList<>();
		List<String> entryDatesInOrder = new ArrayList<>();
		content.forEach(row -> {
			refTypesInOrder.add(row.get("ref_type").asText());
			entryDatesInOrder.add(row.get("entry_date").asText());
		});
		assertThat(refTypesInOrder).contains("PAYMENT");
		assertThat(entryDatesInOrder).isSorted();
		content.forEach(row -> {
			if (row.get("ref_type").asText().equals("PAYMENT")) {
				assertThat(row.get("entry_date").asText()).startsWith("2026-10-01");
			}
		});
	}

	@Test
	void refTypeFilterReturnsOnlyThatEventType() throws Exception {
		UUID contractId = activateContract("B2222BB", "3171012501900002", "08123456780");
		pay(contractId, "500000.00");

		JsonNode onlyPayments = content(getStatement(contractId, "?ref_type=PAYMENT"));
		assertThat(onlyPayments.size()).isGreaterThanOrEqualTo(1);
		onlyPayments.forEach(row -> assertThat(row.get("ref_type").asText()).isEqualTo("PAYMENT"));

		JsonNode onlyActivation = content(getStatement(contractId, "?ref_type=CONTRACT_ACTIVATION"));
		assertThat(onlyActivation.size()).isEqualTo(2);
		onlyActivation.forEach(row -> assertThat(row.get("ref_type").asText()).isEqualTo("CONTRACT_ACTIVATION"));
	}

	@Test
	void dateRangeBoundariesAreInclusiveInTheBusinessZone() throws Exception {
		UUID contractId = activateContract("B3333CC", "3171012501900003", "08123456781");
		pay(contractId, "500000.00");

		// The whole range: both the activation (2026-01-31) and the payment (2026-10-01) appear.
		JsonNode whole = content(getStatement(contractId, "?from=2026-01-31&to=2026-10-01"));
		assertThat(refTypes(whole)).contains("CONTRACT_ACTIVATION", "PAYMENT");

		// to = the activation day only → the payment on 2026-10-01 is excluded, activation included (inclusive).
		JsonNode activationDayOnly = content(getStatement(contractId, "?to=2026-01-31"));
		assertThat(refTypes(activationDayOnly)).containsOnly("CONTRACT_ACTIVATION");

		// from = the payment day → the activation on 2026-01-31 is excluded, the payment included (inclusive).
		JsonNode paymentDayOnly = content(getStatement(contractId, "?from=2026-10-01"));
		assertThat(refTypes(paymentDayOnly)).containsOnly("PAYMENT");

		// A window before everything is empty.
		JsonNode empty = content(getStatement(contractId, "?from=2025-01-01&to=2025-12-31"));
		assertThat(empty.size()).isZero();
	}

	@Test
	void fromAfterToIsAValidationError() throws Exception {
		UUID contractId = activateContract("B4444DD", "3171012501900004", "08123456782");

		MvcResult result = getStatement(contractId, "?from=2026-10-01&to=2026-01-01");
		assertThat(result.getResponse().getStatus()).isEqualTo(400);
		assertThat(errorCode(result)).isEqualTo("VALIDATION_ERROR");
	}

	@Test
	void anUnknownRefTypeOrDateIsAValidationError() throws Exception {
		UUID contractId = activateContract("B5555EE", "3171012501900005", "08123456783");

		assertThat(getStatement(contractId, "?ref_type=NOPE").getResponse().getStatus()).isEqualTo(400);
		assertThat(getStatement(contractId, "?from=not-a-date").getResponse().getStatus()).isEqualTo(400);
	}

	@Test
	void outOfRangePagingIsAValidationError() throws Exception {
		UUID contractId = activateContract("B6666FF", "3171012501900006", "08123456784");

		assertThat(getStatement(contractId, "?size=0").getResponse().getStatus()).isEqualTo(400);
		assertThat(getStatement(contractId, "?size=101").getResponse().getStatus()).isEqualTo(400);
		assertThat(getStatement(contractId, "?page=-1").getResponse().getStatus()).isEqualTo(400);
	}

	@Test
	void aDraftContractHasAnEmptyStatement() throws Exception {
		UUID draft = commands.create("statement-draft-" + UUID.randomUUID(),
				contractRequest("B7777GG", "3171012501900007", "08123456785")).id();

		JsonNode content = content(getStatement(draft, ""));
		assertThat(content.size()).isZero();
	}

	@Test
	void anUnknownContractIsNotFound() throws Exception {
		MvcResult result = getStatement(UUID.randomUUID(), "");
		assertThat(result.getResponse().getStatus()).isEqualTo(404);
		assertThat(errorCode(result)).isEqualTo("CONTRACT_NOT_FOUND");
	}

	@Test
	void adminAndFinanceAreAllowedManajemenAndNoTokenAreNot() throws Exception {
		UUID contractId = activateContract("B8888HH", "3171012501900008", "08123456786");
		String path = "/api/v1/contracts/" + contractId + "/statement";

		for (AppRole role : new AppRole[]{AppRole.ADMIN_OPERASIONAL, AppRole.FINANCE}) {
			MvcResult allowed = mockMvc.perform(get(path)
					.header("Authorization", "Bearer " + TestJwts.forRoles(actorId, role.name()))).andReturn();
			assertThat(allowed.getResponse().getStatus()).as("role %s", role).isEqualTo(200);
		}

		MvcResult manajemen = mockMvc.perform(get(path)
				.header("Authorization", "Bearer " + TestJwts.forRoles(actorId, AppRole.MANAJEMEN.name()))).andReturn();
		assertThat(manajemen.getResponse().getStatus()).isEqualTo(403);
		assertThat(errorCode(manajemen)).isEqualTo("FORBIDDEN");

		MvcResult noToken = mockMvc.perform(get(path)).andReturn();
		assertThat(noToken.getResponse().getStatus()).isEqualTo(401);
		assertThat(errorCode(noToken)).isEqualTo("UNAUTHORIZED");
	}

	// ---------------------------------------------------------------------------------------------

	private UUID activateContract(String plateNo, String nik, String phone) {
		ContractResponse draft = commands.create("statement-key-" + UUID.randomUUID(),
				contractRequest(plateNo, nik, phone));
		commands.activate(draft.id(), null);
		return draft.id();
	}

	private void pay(UUID contractId, String amount) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/v1/payments")
				.header("Authorization", "Bearer " + token)
				.header("Idempotency-Key", "statement-pay-" + UUID.randomUUID())
				.contentType("application/json")
				.content(objectMapper.writeValueAsString(
						new PaymentRequest(contractId, new BigDecimal(amount), PaymentChannel.CASH))))
				.andReturn();
		assertThat(result.getResponse().getStatus())
				.as("payment returned %s: %s", result.getResponse().getStatus(),
						result.getResponse().getContentAsString())
				.isEqualTo(201);
	}

	private static CreateContractRequest contractRequest(String plateNo, String nik, String phone) {
		return new CreateContractRequest(
				new CreateCustomerRequest("Budi Santoso", nik, phone, "Jakarta"),
				new CreateAssetRequest(AssetType.MOTORCYCLE, "Honda", "Beat", null, plateNo),
				new BigDecimal("20000000.00"), new BigDecimal("4000000.00"), 12, InterestScheme.FLAT,
				new BigDecimal("0.0150"), START_DATE);
	}

	private MvcResult getStatement(UUID contractId, String queryString) throws Exception {
		return mockMvc.perform(get("/api/v1/contracts/" + contractId + "/statement" + queryString)
				.header("Authorization", "Bearer " + token)).andReturn();
	}

	private JsonNode content(MvcResult result) throws Exception {
		assertThat(result.getResponse().getStatus())
				.as("GET returned %s: %s", result.getResponse().getStatus(), result.getResponse().getContentAsString())
				.isEqualTo(200);
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("data").get("content");
	}

	private static List<String> refTypes(JsonNode content) {
		List<String> types = new ArrayList<>();
		content.forEach(row -> types.add(row.get("ref_type").asText()));
		return types;
	}

	private String errorCode(MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("error").get("code").asText();
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
			return new FixedClock(TODAY);
		}
	}
}
