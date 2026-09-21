package com.serfira.contract;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.serfira.TestcontainersConfiguration;
import com.serfira.contract.api.CreateAssetRequest;
import com.serfira.contract.api.CreateContractRequest;
import com.serfira.contract.api.CreateCustomerRequest;
import com.serfira.contract.domain.AssetType;
import com.serfira.contract.domain.InterestScheme;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * B5 — the contract HTTP surface end to end (PRD C-1/C-2/C-4, TS §2.2): the standard envelope, masked
 * PII, audit attribution from the JWT subject, idempotent creation, paging/filtering/sorting and the
 * documented error contract.
 *
 * <p>Uses the real security chain with real HS256 tokens (same pattern as
 * {@code ResourceServerSecurityIT}), so a missing token is a genuine 401 from the filter chain.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ContractApiIT {

	private static final byte[] TEST_SECRET = Base64.getDecoder()
			.decode("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");

	private static final UUID SYSTEM_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

	private static final String NIK = "3171012501900001";
	private static final String PHONE = "08123456789";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	ObjectMapper objectMapper;

	private UUID actorId;
	private String token;

	@BeforeEach
	void resetStateAndSeedActor() {
		truncateDomainTables();
		jdbc.update("delete from app_user where id <> ?", SYSTEM_USER_ID);
		actorId = jdbc.queryForObject("""
				insert into app_user (username, password_hash, full_name, role, is_active, created_at, updated_at)
					values (?, 'test-hash', 'IT Actor', 'ADMIN_OPERASIONAL', TRUE, clock_timestamp(), clock_timestamp())
					returning id
				""", UUID.class, "it-actor-" + UUID.randomUUID());
		token = jwtFor(actorId);
	}

	@AfterEach
	void leaveCleanSharedDatabase() {
		// Leaves nothing behind that references this suite's actor: another suite (e.g.
		// ResourceServerSecurityIT) deletes app_user rows, and a counter/idempotency row carrying
		// our actor id would break that cleanup.
		truncateDomainTables();
	}

	@Test
	void createActivateAndReadBackThroughTheApi() throws Exception {
		MvcResult created = mockMvc.perform(createRequest("api-key-1", contractRequest(NIK, PHONE, "B1234XY",
				"20000000.00", "4000000.00"))).andReturn();

		assertThat(created.getResponse().getStatus()).isEqualTo(201);
		JsonNode draft = data(created);
		String contractId = draft.get("id").asText();
		assertThat(created.getResponse().getHeader("Location"))
				.isEqualTo("http://localhost/api/v1/contracts/" + contractId);
		assertThat(body(created).get("error").isNull()).isTrue();
		assertThat(draft.get("status").asText()).isEqualTo("DRAFT");
		assertThat(draft.get("planned_start_date").asText()).isEqualTo("2026-01-31");
		assertThat(draft.get("start_date").isNull()).isTrue();
		// D6: a draft has no schedule, hence no receivable — reported honestly as null.
		assertThat(draft.get("outstanding").isNull()).isTrue();
		// Money travels as a plain decimal string, never scientific notation (wire contract).
		assertThat(created.getResponse().getContentAsString())
				.contains("\"asset_price\":20000000.00")
				.contains("\"principal\":16000000.00")
				.contains("\"down_payment\":4000000.00")
				.contains("\"interest_rate\":0.0150")
				.contains("\"penalty_rate_daily\":0.0010");
		// principal is computed server-side from asset_price - down_payment.
		assertThat(decimal(draft, "asset_price")).isEqualByComparingTo("20000000.00");
		assertThat(decimal(draft, "down_payment")).isEqualByComparingTo("4000000.00");
		assertThat(decimal(draft, "principal")).isEqualByComparingTo("16000000.00");
		assertThat(decimal(draft, "interest_rate")).isEqualByComparingTo("0.0150");
		assertThat(draft.get("grace_period_days").asInt()).isEqualTo(3);
		assertThat(decimal(draft, "penalty_rate_daily")).isEqualByComparingTo("0.0010");
		assertThat(draft.get("version").asLong()).isZero();

		// PRD C-2: nothing is scheduled before activation.
		assertThat(data(get_("/api/v1/contracts/" + contractId + "/installments")).size()).isZero();

		MvcResult activated = mockMvc.perform(post("/api/v1/contracts/" + contractId + "/activate")
				.header("Authorization", "Bearer " + token)).andReturn();
		assertThat(activated.getResponse().getStatus()).isEqualTo(200);
		JsonNode active = data(activated);
		assertThat(active.get("status").asText()).isEqualTo("ACTIVE");
		assertThat(active.get("start_date").asText()).isEqualTo("2026-01-31");
		assertThat(decimal(active, "outstanding")).isEqualByComparingTo("16000000.00");
		assertThat(active.get("version").asLong()).isEqualTo(1L);

		// Detail and schedule agree with the activation response (list/detail/schedule cross-check).
		JsonNode detail = data(get_("/api/v1/contracts/" + contractId));
		assertThat(detail.get("status").asText()).isEqualTo("ACTIVE");
		assertThat(decimal(detail, "outstanding")).isEqualByComparingTo("16000000.00");
		assertThat(detail.get("customer").get("full_name").asText()).isEqualTo("Budi Santoso");

		JsonNode schedule = data(get_("/api/v1/contracts/" + contractId + "/installments"));
		assertThat(schedule.size()).isEqualTo(12);
		assertThat(schedule.get(0).get("due_date").asText()).isEqualTo("2026-02-28");
		assertThat(decimal(schedule.get(0), "principal_amount")).isEqualByComparingTo("1333333.33");
		assertThat(decimal(schedule.get(0), "interest_amount")).isEqualByComparingTo("240000.00");
		// Future scheduled interest is not receivable yet (PRD §5A / TS §5).
		assertThat(decimal(schedule.get(0), "recognized_interest_amount")).isEqualByComparingTo("0.00");
		assertThat(schedule.get(0).get("status").asText()).isEqualTo("PENDING");
		assertThat(schedule.get(11).get("due_date").asText()).isEqualTo("2027-01-31");
		assertThat(decimal(schedule.get(11), "principal_amount")).isEqualByComparingTo("1333333.37");
		BigDecimal sumOfInstallmentOutstanding = BigDecimal.ZERO;
		for (JsonNode line : schedule) {
			sumOfInstallmentOutstanding = sumOfInstallmentOutstanding.add(decimal(line, "outstanding"));
		}
		assertThat(sumOfInstallmentOutstanding).isEqualByComparingTo("16000000.00");

		JsonNode listed = firstListItem();
		assertThat(listed.get("id").asText()).isEqualTo(contractId);
		assertThat(decimal(listed, "outstanding")).isEqualByComparingTo("16000000.00");
		assertThat(listed.get("customer").get("nik").asText()).isEqualTo("3171********0001");
		assertThat(listed.get("asset").get("plate_no").asText()).isEqualTo("B1234XY");
	}

	@Test
	void customerPiiIsMaskedAndNeverLeavesInPlaintext() throws Exception {
		MvcResult created = mockMvc.perform(createRequest("api-key-pii", contractRequest(NIK, PHONE, "B1234XY",
				"20000000.00", "4000000.00"))).andReturn();

		String rawBody = created.getResponse().getContentAsString();
		assertThat(rawBody).doesNotContain(NIK).doesNotContain("628123456789");
		JsonNode customer = data(created).get("customer");
		assertThat(customer.get("nik").asText()).isEqualTo("3171********0001");
		assertThat(customer.get("phone").asText()).isEqualTo("6281*****789");
	}

	@Test
	void writesAreAttributedToTheAuthenticatedActor() throws Exception {
		MvcResult created = mockMvc.perform(createRequest("api-key-audit", contractRequest(NIK, PHONE, "B1234XY",
				"20000000.00", "4000000.00"))).andReturn();
		UUID contractId = UUID.fromString(data(created).get("id").asText());

		mockMvc.perform(post("/api/v1/contracts/" + contractId + "/activate")
				.header("Authorization", "Bearer " + token));

		assertThat(jdbc.queryForObject("select created_by from contract where id = ?", UUID.class, contractId))
				.isEqualTo(actorId).isNotEqualTo(SYSTEM_USER_ID);
		assertThat(jdbc.queryForObject("""
				select created_by from customer where id = (select customer_id from contract where id = ?)
				""", UUID.class, contractId)).isEqualTo(actorId);
		assertThat(jdbc.queryForObject("""
				select created_by from asset where id = (select asset_id from contract where id = ?)
				""", UUID.class, contractId)).isEqualTo(actorId);
		assertThat(jdbc.queryForObject("select count(*) from installment where contract_id = ? and created_by = ?",
				Long.class, contractId, actorId)).isEqualTo(12L);
	}

	@Test
	void identicalRetryReplaysTheStoredResponseInsteadOfCreatingASecondContract() throws Exception {
		CreateContractRequest request = contractRequest(NIK, PHONE, "B1234XY", "20000000.00", "4000000.00");

		MvcResult first = create("retry-key", request);
		MvcResult second = create("retry-key", request);

		assertThat(first.getResponse().getStatus()).isEqualTo(201);
		assertThat(second.getResponse().getStatus()).isEqualTo(201);
		assertThat(data(second).get("id").asText()).isEqualTo(data(first).get("id").asText());
		assertThat(data(second).get("contract_no").asText()).isEqualTo(data(first).get("contract_no").asText());
		assertThat(jdbc.queryForObject("select count(*) from contract", Long.class)).isEqualTo(1L);
		assertThat(jdbc.queryForObject("""
				select count(*) from idempotency_keys
				where endpoint = 'POST /api/v1/contracts' and status = 'COMPLETED'
				""", Long.class)).isEqualTo(1L);
		// Only the masked projection is stored for replay — never plaintext PII.
		assertThat(jdbc.queryForObject("select response_json from idempotency_keys", String.class))
				.doesNotContain(NIK).doesNotContain("628123456789");
	}

	@Test
	void sameKeyWithADifferentRequestIsAConflict() throws Exception {
		create("shared-key", contractRequest(NIK, PHONE, "B1234XY", "20000000.00", "4000000.00"));

		MvcResult conflicting = create("shared-key",
				contractRequest(NIK, PHONE, "B1234XY", "21000000.00", "4000000.00"));

		assertThat(conflicting.getResponse().getStatus()).isEqualTo(409);
		assertThat(errorCode(conflicting)).isEqualTo("CONFLICT");
		assertThat(jdbc.queryForObject("select count(*) from contract", Long.class)).isEqualTo(1L);
	}

	@Test
	void scaleVariantOfTheIdenticalRequestReplays() throws Exception {
		// 20000000 (scale 0) and 20000000.00 describe the same commercial request.
		MvcResult first = create("scale-key", contractRequest(NIK, PHONE, "B1234XY", "20000000", "4000000"));
		MvcResult second = create("scale-key", contractRequest(NIK, PHONE, "B1234XY", "20000000.00", "4000000.00"));

		assertThat(second.getResponse().getStatus()).isEqualTo(201);
		assertThat(data(second).get("id").asText()).isEqualTo(data(first).get("id").asText());
		assertThat(jdbc.queryForObject("select count(*) from contract", Long.class)).isEqualTo(1L);
	}

	@Test
	void missingIdempotencyKeyIsRejectedAndPersistsNothing() throws Exception {
		MvcResult result = create(null, contractRequest(NIK, PHONE, "B1234XY", "20000000.00", "4000000.00"));

		assertThat(result.getResponse().getStatus()).isEqualTo(400);
		assertThat(errorCode(result)).isEqualTo("VALIDATION_ERROR");
		assertThat(jdbc.queryForObject("select count(*) from contract", Long.class)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from customer", Long.class)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from idempotency_keys", Long.class)).isZero();
	}

	@Test
	void listSupportsPagingStatusFilterSearchAndSort() throws Exception {
		MvcResult first = create("list-key-1", contractRequest(NIK, PHONE, "B1234XY", "20000000.00", "4000000.00"));
		MvcResult second = create("list-key-2",
				namedContractRequest("Siti Rahayu", "3275015506850002", "081298765432", "D5678YZ",
						"200000000.00", "50000000.00"));
		mockMvc.perform(post("/api/v1/contracts/" + data(first).get("id").asText() + "/activate")
				.header("Authorization", "Bearer " + token));

		JsonNode all = data(get_("/api/v1/contracts?page=0&size=20"));
		assertThat(all.get("total_elements").asLong()).isEqualTo(2L);
		assertThat(all.get("total_pages").asInt()).isEqualTo(1);
		assertThat(all.get("page").asInt()).isZero();
		assertThat(all.get("size").asInt()).isEqualTo(20);
		assertThat(all.get("content").size()).isEqualTo(2);
		// Default sort (FE §2.2) is created_at desc, so the newest contract comes first.
		assertThat(all.get("content").get(0).get("contract_no").asText())
				.isEqualTo(data(second).get("contract_no").asText());

		JsonNode activeOnly = data(get_("/api/v1/contracts?status=ACTIVE"));
		assertThat(activeOnly.get("total_elements").asLong()).isEqualTo(1L);
		assertThat(activeOnly.get("content").get(0).get("id").asText()).isEqualTo(data(first).get("id").asText());
		assertThat(decimal(activeOnly.get("content").get(0), "outstanding")).isEqualByComparingTo("16000000.00");

		JsonNode draftOnly = data(get_("/api/v1/contracts?status=DRAFT"));
		assertThat(draftOnly.get("total_elements").asLong()).isEqualTo(1L);
		// D6: no schedule yet, so no receivable.
		assertThat(draftOnly.get("content").get(0).get("outstanding").isNull()).isTrue();

		JsonNode searched = data(get_("/api/v1/contracts?q=siti"));
		assertThat(searched.get("total_elements").asLong()).isEqualTo(1L);
		assertThat(searched.get("content").get(0).get("customer").get("full_name").asText()).isEqualTo("Siti Rahayu");

		JsonNode byNumber = data(get_("/api/v1/contracts?q=" + data(first).get("contract_no").asText()));
		assertThat(byNumber.get("total_elements").asLong()).isEqualTo(1L);

		JsonNode sorted = data(get_("/api/v1/contracts?sort=contract_no,asc"));
		assertThat(sorted.get("content").get(0).get("contract_no").asText())
				.isLessThan(sorted.get("content").get(1).get("contract_no").asText());
	}

	@Test
	void unsupportedPagingAndSortAreRejectedAsValidationErrors() throws Exception {
		assertThat(statusOf(get("/api/v1/contracts?sort=rawPassword,asc"))).isEqualTo(400);
		assertThat(statusOf(get("/api/v1/contracts?sort=createdAt,asc"))).isEqualTo(400);
		assertThat(statusOf(get("/api/v1/contracts?sort=created_at,sideways"))).isEqualTo(400);
		assertThat(statusOf(get("/api/v1/contracts?size=0"))).isEqualTo(400);
		assertThat(statusOf(get("/api/v1/contracts?size=101"))).isEqualTo(400);
		assertThat(statusOf(get("/api/v1/contracts?page=-1"))).isEqualTo(400);
		assertThat(statusOf(get("/api/v1/contracts?status=NOPE"))).isEqualTo(400);
		// A supported sort still works, so the rejections above are not a blanket refusal.
		assertThat(statusOf(get("/api/v1/contracts?sort=created_at,desc"))).isEqualTo(200);
	}

	@Test
	void unknownContractIsNotFoundOnEveryReadAndOnActivation() throws Exception {
		UUID unknown = UUID.randomUUID();

		MvcResult detail = mockMvc.perform(get("/api/v1/contracts/" + unknown)
				.header("Authorization", "Bearer " + token)).andReturn();
		assertThat(detail.getResponse().getStatus()).isEqualTo(404);
		assertThat(errorCode(detail)).isEqualTo("CONTRACT_NOT_FOUND");

		MvcResult schedule = mockMvc.perform(get("/api/v1/contracts/" + unknown + "/installments")
				.header("Authorization", "Bearer " + token)).andReturn();
		assertThat(schedule.getResponse().getStatus()).isEqualTo(404);
		assertThat(errorCode(schedule)).isEqualTo("CONTRACT_NOT_FOUND");

		MvcResult activation = mockMvc.perform(post("/api/v1/contracts/" + unknown + "/activate")
				.header("Authorization", "Bearer " + token)).andReturn();
		assertThat(activation.getResponse().getStatus()).isEqualTo(404);
		assertThat(errorCode(activation)).isEqualTo("CONTRACT_NOT_FOUND");
	}

	@Test
	void invalidPayloadsAreRejectedWithTypedValidationErrors() throws Exception {
		// NIK must be exactly 16 digits (review H-2).
		MvcResult shortNik = create("bad-nik-key",
				contractRequest("123", PHONE, "B1234XY", "20000000.00", "4000000.00"));
		assertThat(shortNik.getResponse().getStatus()).isEqualTo(400);
		assertThat(errorCode(shortNik)).isEqualTo("VALIDATION_ERROR");

		// Down payment must be strictly below the asset price.
		MvcResult equalDownPayment = create("bad-dp-key",
				contractRequest(NIK, PHONE, "B1234XY", "20000000.00", "20000000.00"));
		assertThat(equalDownPayment.getResponse().getStatus()).isEqualTo(400);
		assertThat(errorCode(equalDownPayment)).isEqualTo("VALIDATION_ERROR");

		// A monthly rate above 100% is not a rate the servicing rules support.
		MvcResult wildRate = create("bad-rate-key",
				contractRequestWithRate(NIK, PHONE, "B1234XY", "20000000.00", "4000000.00", "1.5000"));
		assertThat(wildRate.getResponse().getStatus()).isEqualTo(400);

		// More precision than the column scale is rejected instead of silently rounded.
		MvcResult overPrecise = create("bad-scale-key",
				contractRequest(NIK, PHONE, "B1234XY", "20000000.001", "4000000.00"));
		assertThat(overPrecise.getResponse().getStatus()).isEqualTo(400);

		assertThat(jdbc.queryForObject("select count(*) from contract", Long.class)).isZero();
	}

	@Test
	void conflictingCustomerIdentityIsRejected() throws Exception {
		create("identity-key-1", contractRequest(NIK, PHONE, "B1234XY", "20000000.00", "4000000.00"));

		// Same NIK, different phone: the system refuses to guess which identity is correct.
		MvcResult conflicting = create("identity-key-2",
				contractRequest(NIK, "081200000999", "B9999ZZ", "20000000.00", "4000000.00"));

		assertThat(conflicting.getResponse().getStatus()).isEqualTo(409);
		assertThat(errorCode(conflicting)).isEqualTo("CONFLICT");
		assertThat(jdbc.queryForObject("select count(*) from customer", Long.class)).isEqualTo(1L);
	}

	@Test
	void aSecondLiveContractForTheSameCustomerAndAssetIsRejected() throws Exception {
		MvcResult first = create("live-key-1", contractRequest(NIK, PHONE, "B1234XY", "20000000.00", "4000000.00"));
		String firstContractId = data(first).get("id").asText();
		String customerId = data(first).get("customer").get("id").asText();

		// Invariant 18: the asset is already financed for this customer.
		MvcResult duplicate = create("live-key-2", contractRequest(NIK, PHONE, "B1234XY", "20000000.00", "4000000.00"));
		assertThat(duplicate.getResponse().getStatus()).isEqualTo(409);
		assertThat(errorCode(duplicate)).isEqualTo("DUPLICATE_CONTRACT");

		// A different asset for the same customer is allowed and reuses the customer row.
		MvcResult otherAsset = create("live-key-3", contractRequest(NIK, PHONE, "B9999ZZ", "20000000.00", "4000000.00"));
		assertThat(otherAsset.getResponse().getStatus()).isEqualTo(201);
		assertThat(data(otherAsset).get("customer").get("id").asText()).isEqualTo(customerId);
		assertThat(jdbc.queryForObject("select count(*) from customer", Long.class)).isEqualTo(1L);

		// Closing the live contract releases the asset, and the asset row is reused (not duplicated).
		jdbc.update("""
				update contract set status = 'CLOSED', closed_at = clock_timestamp(), closed_reason = 'SETTLEMENT'
				where id = ?
				""", UUID.fromString(firstContractId));
		MvcResult refinanced = create("live-key-4", contractRequest(NIK, PHONE, "B1234XY", "20000000.00", "4000000.00"));
		assertThat(refinanced.getResponse().getStatus()).isEqualTo(201);
		assertThat(data(refinanced).get("asset").get("id").asText())
				.isEqualTo(data(first).get("asset").get("id").asText());
		assertThat(jdbc.queryForObject("select count(*) from asset", Long.class)).isEqualTo(2L);
	}

	@Test
	void activationIsIdempotentAndAConflictingStartDateIsRejected() throws Exception {
		MvcResult created = create("activate-key", contractRequest(NIK, PHONE, "B1234XY", "20000000.00", "4000000.00"));
		String contractId = data(created).get("id").asText();

		// An explicit date overrides the planned date (real disbursement date).
		MvcResult overridden = mockMvc.perform(post("/api/v1/contracts/" + contractId + "/activate")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"start_date\":\"2026-02-10\"}")).andReturn();
		assertThat(overridden.getResponse().getStatus()).isEqualTo(200);
		assertThat(data(overridden).get("start_date").asText()).isEqualTo("2026-02-10");
		assertThat(data(overridden).get("planned_start_date").asText()).isEqualTo("2026-01-31");

		// Repeat with no body: idempotent, nothing written again.
		MvcResult repeat = mockMvc.perform(post("/api/v1/contracts/" + contractId + "/activate")
				.header("Authorization", "Bearer " + token)).andReturn();
		assertThat(repeat.getResponse().getStatus()).isEqualTo(200);
		assertThat(data(repeat).get("start_date").asText()).isEqualTo("2026-02-10");
		assertThat(data(repeat).get("version").asLong()).isEqualTo(1L);

		// A different effective date on an already active contract is a state conflict.
		MvcResult conflicting = mockMvc.perform(post("/api/v1/contracts/" + contractId + "/activate")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"start_date\":\"2026-03-01\"}")).andReturn();
		assertThat(conflicting.getResponse().getStatus()).isEqualTo(409);
		assertThat(errorCode(conflicting)).isEqualTo("CONTRACT_STATE_INVALID");

		assertThat(jdbc.queryForObject("select count(*) from installment where contract_id = ?",
				Long.class, UUID.fromString(contractId))).isEqualTo(12L);
		assertThat(jdbc.queryForObject("select to_char(min(due_date), 'YYYY-MM-DD') from installment "
				+ "where contract_id = ?", String.class, UUID.fromString(contractId))).isEqualTo("2026-03-10");
	}

	@Test
	void activationWithoutABodyOrWithAnEmptyBodyUsesThePlannedDate() throws Exception {
		MvcResult withoutBody = create("activate-nobody",
				contractRequest(NIK, PHONE, "B1234XY", "20000000.00", "4000000.00"));
		MvcResult emptyBody = create("activate-emptybody",
				contractRequest(NIK, PHONE, "B9999ZZ", "20000000.00", "4000000.00"));

		MvcResult noBodyResult = mockMvc.perform(post("/api/v1/contracts/"
				+ data(withoutBody).get("id").asText() + "/activate")
				.header("Authorization", "Bearer " + token)).andReturn();
		MvcResult emptyBodyResult = mockMvc.perform(post("/api/v1/contracts/"
				+ data(emptyBody).get("id").asText() + "/activate")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}")).andReturn();

		assertThat(noBodyResult.getResponse().getStatus()).isEqualTo(200);
		assertThat(data(noBodyResult).get("start_date").asText()).isEqualTo("2026-01-31");
		assertThat(emptyBodyResult.getResponse().getStatus()).isEqualTo(200);
		assertThat(data(emptyBodyResult).get("start_date").asText()).isEqualTo("2026-01-31");
	}

	@Test
	void unauthenticatedRequestsAreRejectedAndPersistNothing() throws Exception {
		MvcResult createResult = mockMvc.perform(post("/api/v1/contracts")
				.header("Idempotency-Key", "no-auth-key")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(
						contractRequest(NIK, PHONE, "B1234XY", "20000000.00", "4000000.00")))).andReturn();
		assertThat(createResult.getResponse().getStatus()).isEqualTo(401);
		assertThat(errorCode(createResult)).isEqualTo("UNAUTHORIZED");

		assertThat(mockMvc.perform(get("/api/v1/contracts")).andReturn().getResponse().getStatus()).isEqualTo(401);

		assertThat(jdbc.queryForObject("select count(*) from contract", Long.class)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from customer", Long.class)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from asset", Long.class)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from idempotency_keys", Long.class)).isZero();
	}

	// -------------------------------------------------------------------------------------
	// Request builders & response helpers
	// -------------------------------------------------------------------------------------

	private MockHttpServletRequestBuilder createRequest(String idempotencyKey, CreateContractRequest request)
			throws Exception {
		MockHttpServletRequestBuilder builder = post("/api/v1/contracts")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(request));
		if (idempotencyKey != null) {
			builder = builder.header("Idempotency-Key", idempotencyKey);
		}
		return builder;
	}

	private MvcResult create(String idempotencyKey, CreateContractRequest request) throws Exception {
		return mockMvc.perform(createRequest(idempotencyKey, request)).andReturn();
	}

	private JsonNode get_(String path) throws Exception {
		MvcResult result = mockMvc.perform(get(path).header("Authorization", "Bearer " + token)).andReturn();
		assertThat(result.getResponse().getStatus())
				.as("GET %s returned %s", path, result.getResponse().getContentAsString())
				.isEqualTo(200);
		return body(result);
	}

	private int statusOf(MockHttpServletRequestBuilder request) throws Exception {
		return mockMvc.perform(request.header("Authorization", "Bearer " + token))
				.andReturn().getResponse().getStatus();
	}

	private JsonNode firstListItem() throws Exception {
		return data(get_("/api/v1/contracts")).get("content").get(0);
	}

	private JsonNode body(MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString());
	}

	private JsonNode data(MvcResult result) throws Exception {
		return body(result).get("data");
	}

	/** Envelope unwrap for bodies already parsed (see {@link #get_}). */
	private JsonNode data(JsonNode envelope) {
		return envelope.get("data");
	}

	private String errorCode(MvcResult result) throws Exception {
		return body(result).get("error").get("code").asText();
	}

	private static CreateContractRequest contractRequest(String nik, String phone, String plateNo,
			String assetPrice, String downPayment) {
		return namedContractRequest("Budi Santoso", nik, phone, plateNo, assetPrice, downPayment);
	}

	private static CreateContractRequest namedContractRequest(String fullName, String nik, String phone,
			String plateNo, String assetPrice, String downPayment) {
		return contractRequestWithRate(fullName, nik, phone, plateNo, assetPrice, downPayment, "0.0150");
	}

	private static CreateContractRequest contractRequestWithRate(String nik, String phone, String plateNo,
			String assetPrice, String downPayment, String interestRate) {
		return contractRequestWithRate("Budi Santoso", nik, phone, plateNo, assetPrice, downPayment, interestRate);
	}

	private static CreateContractRequest contractRequestWithRate(String fullName, String nik, String phone,
			String plateNo, String assetPrice, String downPayment, String interestRate) {
		return new CreateContractRequest(
				new CreateCustomerRequest(fullName, nik, phone, "Jakarta"),
				new CreateAssetRequest(AssetType.MOTORCYCLE, "Honda", "Beat", null, plateNo),
				new BigDecimal(assetPrice), new BigDecimal(downPayment), 12, InterestScheme.FLAT,
				new BigDecimal(interestRate), LocalDate.of(2026, 1, 31));
	}

	private static BigDecimal decimal(JsonNode node, String field) {
		return node.get(field).decimalValue();
	}

	/** Mirrors serfira.security.jwt.secret-base64 in src/test/resources/application.properties. */
	private String jwtFor(UUID subject) {
		try {
			JWTClaimsSet claims = new JWTClaimsSet.Builder()
					.subject(subject.toString())
					.claim("roles", java.util.List.of("ADMIN_OPERASIONAL"))
					.issueTime(Date.from(Instant.now()))
					.expirationTime(Date.from(Instant.now().plusSeconds(600)))
					.build();
			SignedJWT signedJwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
			SecretKey key = new SecretKeySpec(TEST_SECRET, "HmacSHA256");
			JWSSigner signer = new MACSigner(key);
			signedJwt.sign(signer);
			return signedJwt.serialize();
		} catch (Exception ex) {
			throw new IllegalStateException("failed to mint the IT bearer token", ex);
		}
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