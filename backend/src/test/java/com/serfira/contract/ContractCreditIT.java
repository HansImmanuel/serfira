package com.serfira.contract;

import com.serfira.contract.api.ContractResponse;
import com.serfira.contract.api.CreateAssetRequest;
import com.serfira.contract.api.CreateContractRequest;
import com.serfira.contract.api.CreateCustomerRequest;
import com.serfira.TestcontainersConfiguration;
import com.serfira.contract.application.ContractCommandService;
import com.serfira.contract.domain.AssetType;
import com.serfira.contract.domain.InterestScheme;
import com.serfira.shared.clock.Clock;
import com.serfira.shared.clock.FixedClock;
import com.serfira.support.TestJwts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * E3 — contract credit end to end (task T14, 04_GAPS_ADDENDUM.md §2): an overpayment becomes a durable
 * {@code contract_credit} row, the credit is applied to recognized receivable through
 * {@code POST /api/v1/contracts/{id}/credit/apply}, and the balance/history read through
 * {@code GET /api/v1/contracts/{id}/credit}. Real commits (MockMvc, no test transaction) so the V14
 * deferred triggers fire exactly where they do in production.
 */
@Import({TestcontainersConfiguration.class, ContractCreditClockTestConfiguration.class})
@SpringBootTest
@AutoConfigureMockMvc
class ContractCreditIT {

	private static final UUID SYSTEM_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final LocalDate BUSINESS_DATE = ContractCreditClockTestConfiguration.BUSINESS_DATE;

	private static final String ADMIN = "ADMIN_OPERASIONAL";
	private static final String FINANCE = "FINANCE";

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
					values (?, 'test-hash', 'Credit IT Actor', 'ADMIN_OPERASIONAL', TRUE,
						clock_timestamp(), clock_timestamp())
					returning id""", UUID.class, "credit-it-actor-" + UUID.randomUUID());
		adminToken = TestJwts.forRoles(actorId, ADMIN);
		fixedClock().setDate(BUSINESS_DATE);

		ContractResponse draft = contracts.create("credit-it-contract-" + UUID.randomUUID(),
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
	void anOverpaymentBooksOneAvailableCreditRowAndNoSecondJournal() throws Exception {
		String excess = "10426666.67";

		MvcResult payment = pay("overpayment", "12000000.00");
		assertThat(payment.getResponse().getStatus()).isEqualTo(201);
		UUID paymentId = UUID.fromString(data(payment).get("id").asText());

		// Exactly one credit, AVAILABLE, equal to the excess, linked to the EXCESS allocation id.
		Map<String, Object> credit = jdbc.queryForMap(
				"select amount, status, source_payment_allocation_id from contract_credit where contract_id = ?",
				contractId);
		assertThat((BigDecimal) credit.get("amount")).isEqualByComparingTo(excess);
		assertThat(credit.get("status")).isEqualTo("AVAILABLE");
		UUID excessAllocationId = jdbc.queryForObject(
				"select id from payment_allocation where payment_id = ? and allocation_type = 'EXCESS'",
				UUID.class, paymentId);
		assertThat(credit.get("source_payment_allocation_id")).isEqualTo(excessAllocationId);

		// The credit row is a sub-ledger, not a journal: no CREDIT_APPLICATION entry, the only EXCESS
		// accounting is the payment's own EXCESS -> TITIPAN_NASABAH line.
		assertThat(count("contract_credit")).isEqualTo(1L);
		assertThat(journalEntryCount("CREDIT_APPLICATION")).isZero();
		assertThat(creditByAccount(onlyEntry("PAYMENT", paymentId)).get("TITIPAN_NASABAH"))
				.isEqualByComparingTo(excess);

		// The read endpoint reports the same balance.
		JsonNode read = data(getCredit(adminToken));
		assertThat(new BigDecimal(read.get("total_credit").asText())).isEqualByComparingTo(excess);
		assertThat(new BigDecimal(read.get("available_balance").asText())).isEqualByComparingTo(excess);
		assertThat(read.get("applications")).isEmpty();
	}

	@Test
	void aRetriedOverpaymentNeverBooksTheCreditTwice() throws Exception {
		MvcResult first = pay("retried-overpayment", "12000000.00");
		assertThat(first.getResponse().getStatus()).isEqualTo(201);
		MvcResult replay = pay("retried-overpayment", "12000000.00");
		assertThat(replay.getResponse().getStatus()).isEqualTo(201);

		// Idempotent replay returns the same payment and does not book a second credit
		// (uk_contract_credit_source backstops it even if the replay path changed).
		assertThat(count("contract_credit")).isEqualTo(1L);
	}

	@Test
	void partialThenFullApplyAcrossTwoInstallmentsFlipsTheCreditToApplied() throws Exception {
		// Credit is created early (before any due date) so it resolves nothing: a pure-excess payment on
		// 2026-01-15 books a 2,000,000 AVAILABLE credit and touches no installment.
		fixedClock().setDate(LocalDate.of(2026, 1, 15));
		MvcResult overpayment = pay("make-credit", "2000000.00");
		assertThat(overpayment.getResponse().getStatus()).isEqualTo(201);
		BigDecimal availableBefore = new BigDecimal(data(getCredit(adminToken)).get("available_balance").asText());
		assertThat(availableBefore).isEqualByComparingTo("2000000.00");

		// Move to a date where periods 1 and 2 are due and bill them with a small payment, leaving most of
		// their recognized receivable unresolved (so the 2,000,000 credit fits inside it, spanning both).
		fixedClock().setDate(LocalDate.of(2026, 3, 31));
		MvcResult billing = pay("bill-two-periods", "50000.00");
		assertThat(billing.getResponse().getStatus()).isEqualTo(201);

		BigDecimal period1PaidBefore = paidAmountOf(1);
		BigDecimal period2PaidBefore = paidAmountOf(2);

		// Partial apply: part of the balance, credit stays AVAILABLE.
		BigDecimal partial = new BigDecimal("1000000.00");
		MvcResult partialApply = applyCredit("{\"amount\":1000000.00}");
		assertThat(partialApply.getResponse().getStatus()).isEqualTo(200);
		JsonNode partialData = data(partialApply);
		assertThat(new BigDecimal(partialData.get("applied_amount").asText())).isEqualByComparingTo(partial);
		assertThat(new BigDecimal(partialData.get("available_balance").asText()))
				.isEqualByComparingTo(availableBefore.subtract(partial));
		assertThat(creditStatuses()).contains("AVAILABLE").doesNotContain("APPLIED");
		UUID firstApplyEntry = latestEntry("CREDIT_APPLICATION");
		assertBalancedCreditJournal(firstApplyEntry);

		// Full apply of the remaining balance (amount omitted): consumes it, credit flips APPLIED.
		MvcResult fullApply = applyCredit("{}");
		assertThat(fullApply.getResponse().getStatus()).isEqualTo(200);
		JsonNode fullData = data(fullApply);
		assertThat(new BigDecimal(fullData.get("available_balance").asText())).isEqualByComparingTo("0.00");
		assertThat(creditStatuses()).containsOnly("APPLIED");
		assertBalancedCreditJournal(latestEntry("CREDIT_APPLICATION"));

		// Credit reduced recognized receivable across the two oldest due installments: paid_amount rose,
		// oldest first, and never on the not-yet-due period 3.
		assertThat(paidAmountOf(1)).isGreaterThan(period1PaidBefore);
		assertThat(paidAmountOf(2)).isGreaterThanOrEqualTo(period2PaidBefore);
		assertThat(paidAmountOf(3)).isEqualByComparingTo("0.00");
		assertThat(installmentStatusOf(3)).isEqualTo("PENDING");

		// Σ applications == amount for the (now APPLIED) credit, and every apply posted one balanced entry.
		assertThat(jdbc.queryForObject("""
				select count(*) from contract_credit c
				where c.amount <> (select coalesce(sum(a.amount),0) from contract_credit_application a
					where a.credit_id = c.id)
				""", Long.class)).isZero();
		assertThat(journalEntryCount("CREDIT_APPLICATION")).isEqualTo(2L);

		// The read endpoint lists every application, oldest first.
		JsonNode read = data(getCredit(adminToken));
		assertThat(new BigDecimal(read.get("available_balance").asText())).isEqualByComparingTo("0.00");
		assertThat(read.get("applications")).isNotEmpty();
	}

	@Test
	void overApplyingMoreThanTheBalanceIsRejectedAndWritesNothing() throws Exception {
		pay("over-apply-credit", "12000000.00");
		BigDecimal available = new BigDecimal(data(getCredit(adminToken)).get("available_balance").asText());
		long applicationsBefore = count("contract_credit_application");

		MvcResult result = applyCredit("{\"amount\":" + available.add(BigDecimal.ONE).toPlainString() + "}");

		assertThat(result.getResponse().getStatus()).isEqualTo(409);
		assertThat(errorCode(result)).isEqualTo("CREDIT_EXCEEDS_BALANCE");
		assertThat(count("contract_credit_application")).isEqualTo(applicationsBefore);
		assertThat(journalEntryCount("CREDIT_APPLICATION")).isZero();
		assertThat(creditStatuses()).containsOnly("AVAILABLE");
	}

	@Test
	void applyingWithNoRecognizedReceivableIsRejectedAndWritesNothing() throws Exception {
		// A payment before any due date is pure excess: a credit exists, but no installment is due, so
		// there is no recognized receivable to apply it against.
		fixedClock().setDate(LocalDate.of(2026, 1, 15));
		MvcResult overpayment = pay("early-credit", "3000000.00");
		assertThat(overpayment.getResponse().getStatus()).isEqualTo(201);
		assertThat(count("contract_credit")).isEqualTo(1L);

		MvcResult result = applyCredit("{}");

		assertThat(result.getResponse().getStatus()).isEqualTo(409);
		assertThat(errorCode(result)).isEqualTo("CREDIT_NOT_APPLICABLE");
		assertThat(count("contract_credit_application")).isZero();
		assertThat(journalEntryCount("CREDIT_APPLICATION")).isZero();
		assertThat(creditStatuses()).containsOnly("AVAILABLE");
	}

	@Test
	void applyingWithoutAnyCreditIsRejected() throws Exception {
		MvcResult result = applyCredit("{}");

		assertThat(result.getResponse().getStatus()).isEqualTo(409);
		assertThat(errorCode(result)).isEqualTo("CREDIT_NOT_APPLICABLE");
		assertThat(count("contract_credit_application")).isZero();
	}

	@Test
	void aMalformedAmountIsAValidationError() throws Exception {
		pay("malformed-amount-credit", "12000000.00");

		MvcResult subCent = applyCredit("{\"amount\":0.001}");
		MvcResult negative = applyCredit("{\"amount\":-100.00}");

		assertThat(subCent.getResponse().getStatus()).isEqualTo(400);
		assertThat(errorCode(subCent)).isEqualTo("VALIDATION_ERROR");
		assertThat(negative.getResponse().getStatus()).isEqualTo(400);
		assertThat(errorCode(negative)).isEqualTo("VALIDATION_ERROR");
		assertThat(count("contract_credit_application")).isZero();
	}

	@Test
	void theV14CapRejectsApplicationsSummingAboveTheCreditEvenViaRawSql() throws Exception {
		pay("raw-sql-cap", "12000000.00");
		Map<String, Object> credit = jdbc.queryForMap(
				"select id, amount from contract_credit where contract_id = ?", contractId);
		UUID creditId = (UUID) credit.get("id");
		BigDecimal amount = (BigDecimal) credit.get("amount");
		UUID installmentId = jdbc.queryForObject(
				"select id from installment where contract_id = ? and period_no = 1", UUID.class, contractId);

		// Insert an application summing above the credit amount directly: the V14 deferred trigger must
		// reject it at commit (JdbcTemplate autocommits each statement).
		assertThatThrownBy(() -> jdbc.update("""
				insert into contract_credit_application
					(credit_id, installment_id, amount, applied_at, created_at, updated_at)
					values (?, ?, ?, clock_timestamp(), clock_timestamp(), clock_timestamp())
				""", creditId, installmentId, amount.add(BigDecimal.ONE)))
				.isInstanceOf(DataAccessException.class)
				.hasMessageContaining("exceed credit amount");

		assertThat(count("contract_credit_application")).isZero();
	}

	@Test
	void readIsAllowedForFinanceButApplyIsNot() throws Exception {
		pay("rbac-credit", "12000000.00");
		String financeToken = TestJwts.forRoles(actorId, FINANCE);

		assertThat(getCredit(financeToken).getResponse().getStatus()).isEqualTo(200);

		MvcResult financeApply = mockMvc.perform(post("/api/v1/contracts/" + contractId + "/credit/apply")
						.header("Authorization", "Bearer " + financeToken)
						.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andReturn();
		assertThat(financeApply.getResponse().getStatus()).isEqualTo(403);
		assertThat(count("contract_credit_application")).isZero();
	}

	@Test
	void applyingToAnUnknownContractIsNotFound() throws Exception {
		MvcResult result = mockMvc.perform(post("/api/v1/contracts/" + UUID.randomUUID() + "/credit/apply")
						.header("Authorization", "Bearer " + adminToken)
						.header("Idempotency-Key", "unknown-contract-apply")
						.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(404);
		assertThat(errorCode(result)).isEqualTo("CONTRACT_NOT_FOUND");
	}

	// --- helpers ---------------------------------------------------------------------------------------

	@Test
	void aMissingIdempotencyKeyIsAValidationErrorAndAppliesNothing() throws Exception {
		seedAppliableCredit();

		MvcResult result = mockMvc.perform(post("/api/v1/contracts/" + contractId + "/credit/apply")
						.header("Authorization", "Bearer " + adminToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"amount\":1000000.00}"))
				.andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(400);
		assertThat(errorCode(result)).isEqualTo("VALIDATION_ERROR");
		assertThat(count("contract_credit_application")).isZero();
		assertThat(journalEntryCount("CREDIT_APPLICATION")).isZero();
		assertThat(availableBalance()).isEqualByComparingTo("2000000.00");
	}

	@Test
	void aRetryAfterALostResponseReplaysAndAppliesTheCreditExactlyOnce() throws Exception {
		seedAppliableCredit();

		// The first call commits, but the caller never sees its response (the exchange is simply dropped).
		MvcResult lost = applyCredit("apply-lost-response", "{\"amount\":1000000.00}");
		assertThat(lost.getResponse().getStatus()).isEqualTo(200);
		long applicationsAfterFirst = count("contract_credit_application");
		BigDecimal paidAfterFirst = paidAmountOf(1).add(paidAmountOf(2));
		assertThat(availableBalance()).isEqualByComparingTo("1000000.00");

		// The operator retries with the same key and body: the stored response comes back, nothing is redone.
		MvcResult retry = applyCredit("apply-lost-response", "{\"amount\":1000000.00}");

		assertThat(retry.getResponse().getStatus()).isEqualTo(200);
		assertThat(data(retry).get("applied_amount").asText()).isEqualTo(data(lost).get("applied_amount").asText());
		assertThat(data(retry).get("applications").get(0).get("application_id").asText())
				.isEqualTo(data(lost).get("applications").get(0).get("application_id").asText());
		assertThat(count("contract_credit_application")).isEqualTo(applicationsAfterFirst);
		assertThat(journalEntryCount("CREDIT_APPLICATION")).isEqualTo(1L);
		assertThat(paidAmountOf(1).add(paidAmountOf(2))).isEqualByComparingTo(paidAfterFirst);
		assertThat(availableBalance()).isEqualByComparingTo("1000000.00");
		assertThat(applyClaims()).isEqualTo(1L);
		assertBalancedCreditJournal(latestEntry("CREDIT_APPLICATION"));
	}

	@Test
	void aDifferentRequestUnderTheSameKeyConflictsAndAppliesNothingMore() throws Exception {
		seedAppliableCredit();
		assertThat(applyCredit("shared-apply-key", "{\"amount\":1000000.00}").getResponse().getStatus())
				.isEqualTo(200);
		long applications = count("contract_credit_application");

		MvcResult otherAmount = applyCredit("shared-apply-key", "{\"amount\":500000.00}");
		// Omitting the amount ("apply everything") is a different request from naming today's balance.
		MvcResult fullBalance = applyCredit("shared-apply-key", "{}");

		assertThat(otherAmount.getResponse().getStatus()).isEqualTo(409);
		assertThat(errorCode(otherAmount)).isEqualTo("CONFLICT");
		assertThat(fullBalance.getResponse().getStatus()).isEqualTo(409);
		assertThat(errorCode(fullBalance)).isEqualTo("CONFLICT");
		assertThat(count("contract_credit_application")).isEqualTo(applications);
		assertThat(journalEntryCount("CREDIT_APPLICATION")).isEqualTo(1L);
		assertThat(availableBalance()).isEqualByComparingTo("1000000.00");
	}

	@Test
	void aNewKeyAfterASuccessfulApplyIsAGenuinelyNewApplication() throws Exception {
		seedAppliableCredit();
		MvcResult first = applyCredit("first-operation", "{\"amount\":1000000.00}");
		MvcResult second = applyCredit("second-operation", "{\"amount\":1000000.00}");

		assertThat(first.getResponse().getStatus()).isEqualTo(200);
		assertThat(second.getResponse().getStatus()).isEqualTo(200);
		assertThat(data(second).get("applications").get(0).get("application_id").asText())
				.isNotEqualTo(data(first).get("applications").get(0).get("application_id").asText());
		assertThat(journalEntryCount("CREDIT_APPLICATION")).isEqualTo(2L);
		assertThat(availableBalance()).isEqualByComparingTo("0.00");
		assertThat(applyClaims()).isEqualTo(2L);
	}

	@Test
	void aRejectedApplyRollsBackItsClaimSoTheKeyCanBeRetriedWithACorrectedAmount() throws Exception {
		seedAppliableCredit();

		MvcResult tooMuch = applyCredit("corrected-retry", "{\"amount\":9000000.00}");
		assertThat(tooMuch.getResponse().getStatus()).isEqualTo(409);
		assertThat(errorCode(tooMuch)).isEqualTo("CREDIT_EXCEEDS_BALANCE");
		assertThat(applyClaims()).isZero();

		MvcResult corrected = applyCredit("corrected-retry", "{\"amount\":1000000.00}");

		assertThat(corrected.getResponse().getStatus()).isEqualTo(200);
		assertThat(journalEntryCount("CREDIT_APPLICATION")).isEqualTo(1L);
		assertThat(applyClaims()).isEqualTo(1L);
	}

	@Test
	void racingRequestsWithOneKeyApplyTheCreditOnce() throws Exception {
		seedAppliableCredit();
		CountDownLatch startTogether = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(3);
		List<String> statuses = new ArrayList<>();
		List<String> applicationIds = new ArrayList<>();
		try {
			List<Future<MvcResult>> futures = new ArrayList<>();
			for (int attempt = 0; attempt < 3; attempt++) {
				futures.add(pool.submit(() -> {
					startTogether.await(5, TimeUnit.SECONDS);
					return applyCredit("racing-apply-key", "{\"amount\":1000000.00}");
				}));
			}
			startTogether.countDown();
			for (Future<MvcResult> future : futures) {
				MvcResult result = future.get(60, TimeUnit.SECONDS);
				statuses.add(String.valueOf(result.getResponse().getStatus()));
				if (result.getResponse().getStatus() == 200) {
					applicationIds.add(data(result).get("applications").get(0).get("application_id").asText());
				}
			}
		} finally {
			pool.shutdownNow();
		}

		// A loser blocks on the claim until the winner commits, then replays: one application, one journal.
		assertThat(applicationIds).isNotEmpty();
		assertThat(applicationIds).containsOnly(applicationIds.get(0));
		assertThat(statuses).allMatch(status -> status.equals("200") || status.equals("409"));
		assertThat(journalEntryCount("CREDIT_APPLICATION")).isEqualTo(1L);
		assertThat(applyClaims()).isEqualTo(1L);
		assertThat(availableBalance()).isEqualByComparingTo("1000000.00");
		assertBalancedCreditJournal(latestEntry("CREDIT_APPLICATION"));
	}

	@Test
	void reusingTheKeyPastRetentionIsRejectedAsExpiredAndAppliesNothingMore() throws Exception {
		seedAppliableCredit();
		assertThat(applyCredit("expiring-apply-key", "{\"amount\":1000000.00}").getResponse().getStatus())
				.isEqualTo(200);

		// ADR-017: past the 7-day window the key is spent, whatever the body.
		fixedClock().advanceBy(Duration.ofDays(8));
		MvcResult sameBody = applyCredit("expiring-apply-key", "{\"amount\":1000000.00}");
		MvcResult otherBody = applyCredit("expiring-apply-key", "{\"amount\":500000.00}");

		assertThat(sameBody.getResponse().getStatus()).isEqualTo(409);
		assertThat(errorCode(sameBody)).isEqualTo("IDEMPOTENCY_KEY_EXPIRED");
		assertThat(errorCode(otherBody)).isEqualTo("IDEMPOTENCY_KEY_EXPIRED");
		assertThat(journalEntryCount("CREDIT_APPLICATION")).isEqualTo(1L);
		assertThat(availableBalance()).isEqualByComparingTo("1000000.00");
	}

	private MvcResult pay(String idempotencyKey, String amount) throws Exception {
		return mockMvc.perform(post("/api/v1/payments")
						.header("Authorization", "Bearer " + adminToken)
						.header("Idempotency-Key", idempotencyKey)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"contract_id\":\"" + contractId + "\",\"amount\":" + amount
								+ ",\"channel\":\"CASH\"}"))
				.andReturn();
	}

	/** Applies credit under a fresh key, i.e. as a genuinely new operation. */
	private MvcResult applyCredit(String jsonBody) throws Exception {
		return applyCredit("credit-" + UUID.randomUUID(), jsonBody);
	}

	private MvcResult applyCredit(String idempotencyKey, String jsonBody) throws Exception {
		return mockMvc.perform(post("/api/v1/contracts/" + contractId + "/credit/apply")
						.header("Authorization", "Bearer " + adminToken)
						.header("Idempotency-Key", idempotencyKey)
						.contentType(MediaType.APPLICATION_JSON)
						.content(jsonBody))
				.andReturn();
	}

	/**
	 * Books a 2,000,000 credit early (nothing due, so nothing resolved), then moves to a date where periods
	 * 1 and 2 are due and billed, leaving plenty of recognized receivable for the credit to be applied to.
	 */
	private void seedAppliableCredit() throws Exception {
		fixedClock().setDate(LocalDate.of(2026, 1, 15));
		assertThat(pay("make-credit", "2000000.00").getResponse().getStatus()).isEqualTo(201);
		fixedClock().setDate(LocalDate.of(2026, 3, 31));
		assertThat(pay("bill-two-periods", "50000.00").getResponse().getStatus()).isEqualTo(201);
	}

	private BigDecimal availableBalance() throws Exception {
		return new BigDecimal(data(getCredit(adminToken)).get("available_balance").asText());
	}

	private long applyClaims() {
		return jdbc.queryForObject("select count(*) from idempotency_keys where endpoint = ?", Long.class,
				"POST /api/v1/contracts/{id}/credit/apply");
	}

	private MvcResult getCredit(String token) throws Exception {
		return mockMvc.perform(get("/api/v1/contracts/" + contractId + "/credit")
						.header("Authorization", "Bearer " + token))
				.andReturn();
	}

	private JsonNode data(MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
	}

	private String errorCode(MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString())
				.get("error").get("code").asText();
	}

	private BigDecimal paidAmountOf(int periodNo) {
		return jdbc.queryForObject("select paid_amount from installment where contract_id = ? and period_no = ?",
				BigDecimal.class, contractId, periodNo);
	}

	private String installmentStatusOf(int periodNo) {
		return jdbc.queryForObject("select status from installment where contract_id = ? and period_no = ?",
				String.class, contractId, periodNo);
	}

	private List<String> creditStatuses() {
		return jdbc.queryForList("select status from contract_credit where contract_id = ?", String.class,
				contractId);
	}

	private long count(String table) {
		return jdbc.queryForObject("select count(*) from " + table, Long.class);
	}

	private long journalEntryCount(String refType) {
		return jdbc.queryForObject("select count(*) from journal_entry where ref_type = ?", Long.class, refType);
	}

	private UUID onlyEntry(String refType, UUID refId) {
		return jdbc.queryForObject("select id from journal_entry where ref_type = ? and ref_id = ?",
				UUID.class, refType, refId);
	}

	private UUID latestEntry(String refType) {
		return jdbc.queryForObject(
				"select id from journal_entry where ref_type = ? order by posted_at desc, id desc limit 1",
				UUID.class, refType);
	}

	private void assertBalancedCreditJournal(UUID entryId) {
		Map<String, BigDecimal> debits = debitByAccount(entryId);
		assertThat(debits).containsOnlyKeys("TITIPAN_NASABAH");
		BigDecimal debitTotal = jdbc.queryForObject(
				"select sum(debit) from journal_line where journal_entry_id = ?", BigDecimal.class, entryId);
		BigDecimal creditTotal = jdbc.queryForObject(
				"select sum(credit) from journal_line where journal_entry_id = ?", BigDecimal.class, entryId);
		assertThat(debitTotal).isEqualByComparingTo(creditTotal);
		// Every credit line is one of the three receivable accounts.
		assertThat(jdbc.queryForObject("""
				select count(*) from journal_line
				where journal_entry_id = ? and credit > 0
					and account_code not in ('PIUTANG_DENDA','PIUTANG_BUNGA','PIUTANG_POKOK')
				""", Long.class, entryId)).isZero();
	}

	private Map<String, BigDecimal> debitByAccount(UUID entryId) {
		java.util.Map<String, BigDecimal> debits = new java.util.LinkedHashMap<>();
		for (Map<String, Object> row : jdbc.queryForList(
				"select account_code, debit from journal_line where journal_entry_id = ? and debit > 0", entryId)) {
			debits.put((String) row.get("account_code"), (BigDecimal) row.get("debit"));
		}
		return debits;
	}

	private Map<String, BigDecimal> creditByAccount(UUID entryId) {
		java.util.Map<String, BigDecimal> credits = new java.util.LinkedHashMap<>();
		for (Map<String, Object> row : jdbc.queryForList(
				"select account_code, credit from journal_line where journal_entry_id = ? and credit > 0", entryId)) {
			credits.put((String) row.get("account_code"), (BigDecimal) row.get("credit"));
		}
		return credits;
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
