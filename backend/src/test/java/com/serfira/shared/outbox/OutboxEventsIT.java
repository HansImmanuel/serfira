package com.serfira.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.serfira.TestcontainersConfiguration;
import com.serfira.contract.api.ContractResponse;
import com.serfira.contract.api.CreateAssetRequest;
import com.serfira.contract.api.CreateContractRequest;
import com.serfira.contract.api.CreateCustomerRequest;
import com.serfira.contract.application.ContractCommandService;
import com.serfira.contract.domain.AssetType;
import com.serfira.contract.domain.InterestScheme;
import com.serfira.payment.PaymentClockTestConfiguration;
import com.serfira.penalty.application.LockedDailyServicingJob;
import com.serfira.shared.clock.Clock;
import com.serfira.shared.clock.FixedClock;
import com.serfira.shared.security.AppRole;
import com.serfira.support.TestJwts;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
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

/**
 * T38 / ADR-027: every business transition writes exactly one outbox row of its type, in its own transaction,
 * with a dedicated PII-free payload, and a replayed, rejected or no-op request writes none.
 *
 * <p>Real commits (MockMvc and the transactional services, no test transaction), as in {@code PaymentVoidIT}.
 * Fixture: the B5 terms (20,000,000 asset, 4,000,000 down payment, FLAT 1.5%) with a planned start of
 * 2026-01-31, so period 1 is due on the pinned business date 2026-02-28. The customer's name, NIK, phone and
 * address are distinctive constants so the PII scan can look for them.
 */
@Import({TestcontainersConfiguration.class, PaymentClockTestConfiguration.class})
@SpringBootTest
@AutoConfigureMockMvc
class OutboxEventsIT {

	private static final UUID SYSTEM_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final LocalDate BUSINESS_DATE = PaymentClockTestConfiguration.BUSINESS_DATE;
	private static final String ADMIN = AppRole.ADMIN_OPERASIONAL.name();

	static final String CUSTOMER_NAME = "Outboxia Rahmawati";
	static final String CUSTOMER_NIK = "3171019901900077";
	static final String CUSTOMER_PHONE = "081299990077";
	static final String CUSTOMER_ADDRESS = "Jalan Outbox Sentinel 77";
	static final String VOID_REASON = "VOID-REASON-SENTINEL";
	static final String WRITE_OFF_REASON = "WRITE-OFF-REASON-SENTINEL";

	private static final String PERIOD_TOTAL_12 = "1573333.33";

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

	@Autowired
	LockedDailyServicingJob dailyJob;

	private final AtomicInteger plates = new AtomicInteger();
	private UUID actorId;
	private String adminToken;

	@BeforeEach
	void seedActor() {
		truncateDomainTables();
		jdbc.update("delete from app_user where id <> ?", SYSTEM_USER_ID);
		actorId = jdbc.queryForObject("""
				insert into app_user (username, password_hash, full_name, role, is_active, created_at, updated_at)
					values (?, 'test-hash', 'Outbox Events IT Actor', 'ADMIN_OPERASIONAL', TRUE,
						clock_timestamp(), clock_timestamp())
					returning id""", UUID.class, "outbox-events-it-actor-" + UUID.randomUUID());
		adminToken = TestJwts.forRoles(actorId, ADMIN);
		fixedClock().setDate(BUSINESS_DATE);
	}

	@AfterEach
	void leaveCleanSharedDatabase() {
		fixedClock().setDate(BUSINESS_DATE);
		truncateDomainTables();
	}

	// --- PaymentReceived ----------------------------------------------------------------------------------

	@Test
	void aPaymentWritesOnePaymentReceived() throws Exception {
		UUID contractId = createAndActivate(12);

		UUID paymentId = paymentId(pay(contractId, "pay-received", PERIOD_TOTAL_12));

		List<Map<String, Object>> rows = rows(paymentId);
		assertThat(rows).hasSize(1);
		assertThat(rows.get(0)).containsEntry("event_type", "PaymentReceived").containsEntry("aggregate_type", "PAYMENT");
		JsonNode payload = payload(rows.get(0));
		assertThat(keys(payload)).containsExactlyInAnyOrder("schema_version", "payment_id", "payment_no",
				"contract_id", "contract_no", "amount", "channel", "paid_at", "excess_amount");
		assertThat(payload.get("schema_version").asInt()).isEqualTo(1);
		assertThat(payload.get("amount").isString()).isTrue();
		assertThat(payload.get("amount").asText()).isEqualTo(PERIOD_TOTAL_12);
		assertThat(payload.get("excess_amount").asText()).isEqualTo("0.00");
		assertThat(payload.get("contract_id").asText()).isEqualTo(contractId.toString());
		assertThat(payload.get("channel").asText()).isEqualTo("CASH");
	}

	@Test
	void aReplayedPaymentKeyWritesNoSecondRow() throws Exception {
		UUID contractId = createAndActivate(12);

		UUID first = paymentId(pay(contractId, "pay-replay", PERIOD_TOTAL_12));
		UUID replay = paymentId(pay(contractId, "pay-replay", PERIOD_TOTAL_12));

		assertThat(replay).isEqualTo(first);
		assertThat(rows(first)).hasSize(1);
	}

	@Test
	void aRejectedPaymentWritesNothing() throws Exception {
		UUID draftId = createDraft(12);

		MvcResult result = pay(draftId, "pay-draft", PERIOD_TOTAL_12);

		assertThat(result.getResponse().getStatus()).isEqualTo(409);
		assertThat(eventsForContract(draftId)).isEmpty();
	}

	@Test
	void aPaymentThatDoesNotCloseTheContractWritesNoContractClosed() throws Exception {
		UUID contractId = createAndActivate(12);

		pay(contractId, "pay-no-close", PERIOD_TOTAL_12);

		assertThat(eventsForContract(contractId)).contains("PaymentReceived").doesNotContain("ContractClosed");
	}

	// --- PaymentVoided ------------------------------------------------------------------------------------

	@Test
	void aVoidWritesPaymentVoidedWithoutTheReason() throws Exception {
		UUID contractId = createAndActivate(12);
		UUID paymentId = paymentId(pay(contractId, "pay-to-void", PERIOD_TOTAL_12));

		MvcResult result = voidPayment(paymentId, "void-1", "{\"reason\":\"" + VOID_REASON + "\"}");

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		List<Map<String, Object>> voided = rows(paymentId).stream()
				.filter(row -> "PaymentVoided".equals(row.get("event_type"))).toList();
		assertThat(voided).hasSize(1);
		JsonNode payload = payload(voided.get(0));
		assertThat(keys(payload)).containsExactlyInAnyOrder("schema_version", "payment_id", "payment_no",
				"contract_id", "amount", "voided_at", "contract_reopened");
		assertThat(payload.get("contract_reopened").asBoolean()).isFalse();
		assertThat(payload.get("amount").asText()).isEqualTo(PERIOD_TOTAL_12);
		assertThat(voided.get(0).get("payload").toString()).doesNotContain(VOID_REASON);
	}

	@Test
	void aVoidThatReopensAMaturityCloseSaysSo() throws Exception {
		UUID contractId = createAndActivate(1);
		UUID paymentId = paymentId(pay(contractId, "pay-to-maturity", installmentTotal(contractId, 1)));
		assertThat(jdbc.queryForObject("select closed_reason from contract where id = ?", String.class, contractId))
				.isEqualTo("MATURITY");

		voidPayment(paymentId, "void-reopen", "{}");

		JsonNode payload = payload(rows(paymentId).stream()
				.filter(row -> "PaymentVoided".equals(row.get("event_type"))).findFirst().orElseThrow());
		assertThat(payload.get("contract_reopened").asBoolean()).isTrue();
	}

	@Test
	void voidingAnAlreadyVoidedPaymentWritesNoSecondEvent() throws Exception {
		UUID contractId = createAndActivate(12);
		UUID paymentId = paymentId(pay(contractId, "pay-void-twice", PERIOD_TOTAL_12));
		voidPayment(paymentId, "void-first", "{}");

		MvcResult again = voidPayment(paymentId, "void-second-fresh-key", "{}");

		assertThat(again.getResponse().getStatus()).isEqualTo(200);
		assertThat(rows(paymentId).stream().filter(row -> "PaymentVoided".equals(row.get("event_type"))))
				.hasSize(1);
	}

	// --- ContractActivated / ContractClosed / ContractWrittenOff -----------------------------------------

	@Test
	void activationWritesOneContractActivated() {
		UUID contractId = createAndActivate(12);

		List<Map<String, Object>> rows = rows(contractId);
		assertThat(rows).hasSize(1);
		assertThat(rows.get(0)).containsEntry("event_type", "ContractActivated").containsEntry("aggregate_type", "CONTRACT");
		JsonNode payload = payload(rows.get(0));
		assertThat(keys(payload)).containsExactlyInAnyOrder("schema_version", "contract_id", "contract_no",
				"start_date", "principal", "tenor_months", "interest_scheme");
		assertThat(payload.get("principal").asText()).isEqualTo("16000000.00");
		assertThat(payload.get("start_date").asText()).isEqualTo("2026-01-31");
		assertThat(payload.get("tenor_months").asInt()).isEqualTo(12);
		assertThat(payload.get("interest_scheme").asText()).isEqualTo("FLAT");
	}

	@Test
	void repeatingActivationWritesNoSecondEvent() {
		UUID contractId = createAndActivate(12);

		contracts.activate(contractId, null);

		assertThat(rows(contractId)).hasSize(1);
	}

	@Test
	void aPaymentThatMaturesTheContractWritesPaymentReceivedThenContractClosed() throws Exception {
		UUID contractId = createAndActivate(1);

		pay(contractId, "pay-matures", installmentTotal(contractId, 1));

		assertThat(eventsForContract(contractId))
				.containsExactly("ContractActivated", "PaymentReceived", "ContractClosed");
		Map<String, Object> closed = rows(contractId).stream()
				.filter(row -> "ContractClosed".equals(row.get("event_type"))).findFirst().orElseThrow();
		JsonNode payload = payload(closed);
		assertThat(keys(payload)).containsExactlyInAnyOrder("schema_version", "contract_id", "contract_no",
				"closed_reason", "closed_at");
		assertThat(payload.get("closed_reason").asText()).isEqualTo("MATURITY");
	}

	@Test
	void aCreditApplicationThatMaturesTheContractWritesContractClosed() throws Exception {
		UUID contractId = createAndActivate(2);
		String secondTotal = installmentTotal(contractId, 2);
		// Period 1 is due, period 2 is not: the second period's share lands as EXCESS credit (ADR-009).
		String both = new BigDecimal(installmentTotal(contractId, 1)).add(new BigDecimal(secondTotal)).toPlainString();
		pay(contractId, "pay-into-credit", both);
		assertThat(eventsForContract(contractId)).doesNotContain("ContractClosed");

		LocalDate secondDue = jdbc.queryForObject(
				"select due_date from installment where contract_id = ? and period_no = 2", LocalDate.class, contractId);
		fixedClock().setDate(secondDue);
		dailyJob.run(secondDue);
		MvcResult apply = mockMvc.perform(post("/api/v1/contracts/" + contractId + "/credit/apply")
						.header("Authorization", "Bearer " + adminToken)
						.header("Idempotency-Key", "apply-matures")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"amount\":" + secondTotal + "}"))
				.andReturn();

		assertThat(apply.getResponse().getStatus()).isEqualTo(200);
		assertThat(eventsForContract(contractId).stream().filter("ContractClosed"::equals)).hasSize(1);
		assertThat(jdbc.queryForObject("select closed_reason from contract where id = ?", String.class, contractId))
				.isEqualTo("MATURITY");
	}

	@Test
	void writeOffWritesContractWrittenOffWithoutTheReason() throws Exception {
		UUID contractId = createAndActivate(12);

		MvcResult result = writeOff(contractId, "write-off-1");

		assertThat(result.getResponse().getStatus()).isEqualTo(201);
		List<Map<String, Object>> writtenOff = rows(contractId).stream()
				.filter(row -> "ContractWrittenOff".equals(row.get("event_type"))).toList();
		assertThat(writtenOff).hasSize(1);
		JsonNode payload = payload(writtenOff.get(0));
		assertThat(keys(payload)).containsExactlyInAnyOrder("schema_version", "contract_id", "contract_no",
				"written_off_at", "total_written_off", "installment_count");
		assertThat(payload.get("total_written_off").isString()).isTrue();
		assertThat((String) writtenOff.get(0).get("payload")).doesNotContain(WRITE_OFF_REASON);
		assertThat(eventsForContract(contractId)).doesNotContain("ContractClosed");
	}

	@Test
	void aReplayedWriteOffKeyWritesNoSecondRow() throws Exception {
		UUID contractId = createAndActivate(12);

		writeOff(contractId, "write-off-replay");
		writeOff(contractId, "write-off-replay");

		assertThat(eventsForContract(contractId).stream().filter("ContractWrittenOff"::equals)).hasSize(1);
	}

	// --- SettlementExecuted, ordering and the PII scan ----------------------------------------------------

	@Test
	void settlementWritesSettlementExecutedThenContractClosed() throws Exception {
		UUID contractId = createAndActivate(12);

		MvcResult result = settle(contractId, "settle-1");

		assertThat(result.getResponse().getStatus()).isEqualTo(201);
		UUID settlementId = UUID.fromString(data(result).get("settlement_id").asText());
		// One transaction: the two rows either differ in created_at (stamped per persist) or tie on it, and then
		// the time-ordered id breaks the tie (ADR-027 D4; the tie itself is pinned by OutboxEventIdsTest).
		// Whether they tie here depends on which context's clock AuditSupport was last bound to, so the test
		// asserts only the order.
		List<Map<String, Object>> pair = jdbc.queryForList("""
				select event_type, aggregate_type, aggregate_id, created_at from outbox_events
				where event_type in ('SettlementExecuted', 'ContractClosed') and payload_json ->> 'contract_id' = ?
				order by created_at, id""", contractId.toString());
		assertThat(pair).extracting(row -> row.get("event_type")).containsExactly("SettlementExecuted", "ContractClosed");
		assertThat(pair.get(0)).containsEntry("aggregate_type", "SETTLEMENT").containsEntry("aggregate_id", settlementId);
		assertThat(pair.get(1)).containsEntry("aggregate_type", "CONTRACT").containsEntry("aggregate_id", contractId);

		JsonNode executed = payload(rows(settlementId).get(0));
		assertThat(keys(executed)).containsExactlyInAnyOrder("schema_version", "settlement_id", "settlement_no",
				"contract_id", "quote_id", "cash_received", "credit_used", "rebate_amount", "admin_fee", "executed_at");
		assertThat(executed.get("cash_received").isString()).isTrue();
		assertThat(payload(rows(contractId).stream()
				.filter(row -> "ContractClosed".equals(row.get("event_type"))).findFirst().orElseThrow())
				.get("closed_reason").asText()).isEqualTo("SETTLEMENT");
	}

	@Test
	void aReplayedSettlementKeyWritesNoSecondRow() throws Exception {
		UUID contractId = createAndActivate(12);
		UUID quoteId = quoteId(contractId);

		execute(quoteId, "settle-replay");
		execute(quoteId, "settle-replay");

		assertThat(eventsForContract(contractId).stream().filter("SettlementExecuted"::equals)).hasSize(1);
		assertThat(eventsForContract(contractId).stream().filter("ContractClosed"::equals)).hasSize(1);
	}

	@Test
	void noPayloadCarriesCustomerDataOrFreeText() throws Exception {
		// One flow of each of the six types, so the scan does not depend on test order.
		UUID paid = createAndActivate(1);
		UUID paymentId = paymentId(pay(paid, "pii-pay", installmentTotal(paid, 1)));
		voidPayment(paymentId, "pii-void", "{\"reason\":\"" + VOID_REASON + "\"}");
		writeOff(createAndActivate(12), "pii-write-off");
		settle(createAndActivate(12), "pii-settle");

		assertThat(jdbc.queryForList("select distinct event_type from outbox_events", String.class))
				.containsExactlyInAnyOrder("ContractActivated", "PaymentReceived", "ContractClosed", "PaymentVoided",
						"ContractWrittenOff", "SettlementExecuted");
		Set<String> forbiddenKeys = Set.of("full_name", "name", "customer_name", "nik", "phone", "address",
				"reason", "void_reason", "write_off_reason", "note");
		for (String payload : jdbc.queryForList("select payload_json::text from outbox_events", String.class)) {
			assertThat(payload).doesNotContain(CUSTOMER_NAME, CUSTOMER_NIK, CUSTOMER_PHONE, CUSTOMER_ADDRESS,
					VOID_REASON, WRITE_OFF_REASON);
			assertThat(keys(objectMapper.readTree(payload))).doesNotContainAnyElementsOf(forbiddenKeys);
		}
	}

	// --- helpers ------------------------------------------------------------------------------------------

	UUID createDraft(int tenorMonths) {
		ContractResponse draft = contracts.create("outbox-it-contract-" + UUID.randomUUID(),
				new CreateContractRequest(
						new CreateCustomerRequest(CUSTOMER_NAME, CUSTOMER_NIK, CUSTOMER_PHONE, CUSTOMER_ADDRESS),
						new CreateAssetRequest(AssetType.MOTORCYCLE, "Honda", "Beat", null,
								"B" + (1000 + plates.incrementAndGet()) + "OBX"),
						new BigDecimal("20000000.00"), new BigDecimal("4000000.00"), tenorMonths,
						InterestScheme.FLAT, new BigDecimal("0.0150"), LocalDate.of(2026, 1, 31)));
		return draft.id();
	}

	UUID createAndActivate(int tenorMonths) {
		UUID contractId = createDraft(tenorMonths);
		contracts.activate(contractId, null);
		return contractId;
	}

	MvcResult pay(UUID contractId, String idempotencyKey, String amount) throws Exception {
		return mockMvc.perform(post("/api/v1/payments")
						.header("Authorization", "Bearer " + adminToken)
						.header("Idempotency-Key", idempotencyKey)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"contract_id\":\"" + contractId + "\",\"amount\":" + amount
								+ ",\"channel\":\"CASH\"}"))
				.andReturn();
	}

	MvcResult voidPayment(UUID paymentId, String idempotencyKey, String body) throws Exception {
		return mockMvc.perform(post("/api/v1/payments/" + paymentId + "/void")
						.header("Authorization", "Bearer " + adminToken)
						.header("Idempotency-Key", idempotencyKey)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andReturn();
	}

	MvcResult writeOff(UUID contractId, String idempotencyKey) throws Exception {
		return mockMvc.perform(post("/api/v1/contracts/" + contractId + "/write-off")
						.header("Authorization", "Bearer " + adminToken)
						.header("Idempotency-Key", idempotencyKey)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"reason\":\"" + WRITE_OFF_REASON + "\"}"))
				.andReturn();
	}

	UUID quoteId(UUID contractId) throws Exception {
		MvcResult quote = mockMvc.perform(post("/api/v1/settlements/quote")
						.header("Authorization", "Bearer " + adminToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"contract_id\":\"" + contractId + "\"}"))
				.andReturn();
		assertThat(quote.getResponse().getStatus()).isEqualTo(200);
		return UUID.fromString(data(quote).get("quote_id").asText());
	}

	MvcResult execute(UUID quoteId, String idempotencyKey) throws Exception {
		return mockMvc.perform(post("/api/v1/settlements")
						.header("Authorization", "Bearer " + adminToken)
						.header("Idempotency-Key", idempotencyKey)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"quote_id\":\"" + quoteId + "\"}"))
				.andReturn();
	}

	MvcResult settle(UUID contractId, String idempotencyKey) throws Exception {
		return execute(quoteId(contractId), idempotencyKey);
	}

	JsonNode data(MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
	}

	UUID paymentId(MvcResult result) throws Exception {
		assertThat(result.getResponse().getStatus()).isEqualTo(201);
		return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString())
				.get("data").get("id").asText());
	}

	/** Principal plus scheduled interest of one installment, read from the row (period 12 absorbs rounding). */
	String installmentTotal(UUID contractId, int periodNo) {
		return jdbc.queryForObject("""
				select (principal_amount + interest_amount)::text from installment
				where contract_id = ? and period_no = ?""", String.class, contractId, periodNo);
	}

	/** Outbox rows for one aggregate, in publisher order. */
	List<Map<String, Object>> rows(UUID aggregateId) {
		return jdbc.queryForList("""
				select event_type, aggregate_type, payload_json::text as payload, created_at
				from outbox_events where aggregate_id = ? order by created_at, id""", aggregateId);
	}

	/** Event types whose payload names this contract, in publisher order. */
	List<String> eventsForContract(UUID contractId) {
		return jdbc.queryForList("""
				select event_type from outbox_events where payload_json ->> 'contract_id' = ?
				order by created_at, id""", String.class, contractId.toString());
	}

	JsonNode payload(Map<String, Object> row) {
		return objectMapper.readTree((String) row.get("payload"));
	}

	static Set<String> keys(JsonNode node) {
		return node.properties().stream().map(Map.Entry::getKey).collect(Collectors.toSet());
	}

	FixedClock fixedClock() {
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
