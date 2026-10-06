package com.serfira.penalty;

import com.serfira.TestcontainersConfiguration;
import com.serfira.contract.api.ContractResponse;
import com.serfira.contract.api.CreateAssetRequest;
import com.serfira.contract.api.CreateContractRequest;
import com.serfira.contract.api.CreateCustomerRequest;
import com.serfira.contract.application.ContractCommandService;
import com.serfira.contract.application.InstallmentBillingPort;
import com.serfira.contract.domain.AssetType;
import com.serfira.contract.domain.InterestScheme;
import com.serfira.penalty.application.EffectivePenaltyPort;
import com.serfira.penalty.application.EffectivePenaltySnapshot;
import com.serfira.penalty.application.InstallmentEffectivePenalty;
import com.serfira.penalty.application.PenaltyAccrualPort;
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
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * E5 — penalty waive/reduce end to end (task T15, ADR-019): the {@code POST /api/v1/penalty-adjustments}
 * endpoint, the correcting {@code PENALTY_WAIVER} journal, the effective-penalty port, the invariant-9 cap
 * (service pre-check + V15 DB backstop), and append-only immutability (V1 trigger), all against a real
 * PostgreSQL 16 and the real security chain.
 *
 * <p>Every request commits for real (MockMvc, no test transaction), so the deferred V3/V15 triggers run
 * exactly as in production. Penalty is accrued through the real D1 path (billing then accrual), never by
 * seeding {@code penalty_amount}, so the port-parity assertion compares against genuinely written rows.
 */
@Import({TestcontainersConfiguration.class, PenaltyAdjustmentIT.AdjustmentClockConfiguration.class})
@SpringBootTest
@AutoConfigureMockMvc
class PenaltyAdjustmentIT {

	private static final UUID SYSTEM_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

	/** Period 1 is due 2026-02-28; 2026-03-05 charges two late days (03-04, 03-05) outside the 3-day grace. */
	private static final LocalDate ACCRUAL_DATE = LocalDate.of(2026, 3, 5);

	/** Two charged days at 1,573.33/day = gross recognized penalty on period 1. */
	private static final String GROSS_PENALTY = "3146.66";

	@TestConfiguration(proxyBeanMethods = false)
	static class AdjustmentClockConfiguration {

		static final LocalDate BUSINESS_DATE = ACCRUAL_DATE;

		@Bean
		@Primary
		Clock fixedClock() {
			return new FixedClock(BUSINESS_DATE);
		}
	}

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	ObjectMapper objectMapper;

	@Autowired
	ContractCommandService contracts;

	@Autowired
	InstallmentBillingPort billing;

	@Autowired
	PenaltyAccrualPort penalty;

	@Autowired
	EffectivePenaltyPort effectivePenalty;

	private UUID actorId;
	private String token;
	private UUID contractId;

	@BeforeEach
	void seedActivatedContractWithAccruedPenalty() {
		truncateDomainTables();
		jdbc.update("delete from app_user where id <> ?", SYSTEM_USER_ID);
		actorId = jdbc.queryForObject("""
				insert into app_user (username, password_hash, full_name, role, is_active, created_at, updated_at)
					values (?, 'test-hash', 'Waiver IT Actor', 'ADMIN_OPERASIONAL', TRUE,
						clock_timestamp(), clock_timestamp())
					returning id
				""", UUID.class, "waiver-it-actor-" + UUID.randomUUID());
		token = TestJwts.forRoles(actorId, AppRole.ADMIN_OPERASIONAL.name());

		ContractResponse draft = contracts.create("waiver-it-contract-" + UUID.randomUUID(),
				new CreateContractRequest(
						new CreateCustomerRequest("Budi Santoso", "3171012501900001", "08123456789", "Jakarta"),
						new CreateAssetRequest(AssetType.MOTORCYCLE, "Honda", "Beat", null, "B1234XY"),
						new BigDecimal("20000000.00"), new BigDecimal("4000000.00"), 12, InterestScheme.FLAT,
						new BigDecimal("0.0150"), LocalDate.of(2026, 1, 31)));
		contracts.activate(draft.id(), null);
		contractId = draft.id();

		// Real D1 path: bill the due interest, then accrue the two late days so period 1 carries gross
		// penalty 3,146.66 (no seeding of penalty_amount).
		billing.billDueInterest(contractId, ACCRUAL_DATE);
		penalty.accrueDuePenalty(contractId, ACCRUAL_DATE);
		assertThat(penaltyAmountOf(1)).isEqualByComparingTo(GROSS_PENALTY);
	}

	@AfterEach
	void leaveCleanSharedDatabase() {
		truncateDomainTables();
	}

	@Test
	void aReductionLowersEffectivePenaltyAndPostsABalancedWaiverJournal() throws Exception {
		MvcResult result = adjust("REDUCE", "1000.00", "goodwill reduction");

		assertThat(result.getResponse().getStatus()).isNotIn(401, 403, 500);
		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		JsonNode body = data(result);
		assertThat(body.get("adjustment_type").asText()).isEqualTo("REDUCE");
		assertThat(decimal(body, "amount")).isEqualByComparingTo("1000.00");
		assertThat(decimal(body, "effective_penalty")).isEqualByComparingTo("2146.66");
		// approved_by is the authenticated actor, never request data.
		assertThat(body.get("approved_by").asText()).isEqualTo(actorId.toString());

		// One PENALTY_WAIVER journal, keyed by the adjustment id, Dr BEBAN_WAIVER_DENDA / Cr PIUTANG_DENDA.
		UUID adjustmentId = UUID.fromString(body.get("id").asText());
		UUID entryId = waiverEntryOf(adjustmentId);
		assertThat(debitsOf(entryId)).containsExactly(Map.entry("BEBAN_WAIVER_DENDA", new BigDecimal("1000.00")));
		assertThat(creditsOf(entryId)).containsExactly(Map.entry("PIUTANG_DENDA", new BigDecimal("1000.00")));
		assertThat(debitTotal(entryId)).isEqualByComparingTo(creditTotal(entryId));
		// Lines carry the contract id for reconciliation.
		assertThat(jdbc.queryForObject("select count(*) from journal_line where journal_entry_id = ? "
				+ "and contract_id <> ?", Long.class, entryId, contractId)).isZero();

		// Accrual history is untouched: the waiver reverses the receivable, not the append-only rows.
		assertThat(accrualCount(1)).isEqualTo(2);
		assertThat(penaltyAmountOf(1)).isEqualByComparingTo(GROSS_PENALTY);
	}

	@Test
	void aFullWaiverDrivesEffectiveToZeroAndTheV3CapFormulaFollows() throws Exception {
		MvcResult waive = adjust("WAIVE", GROSS_PENALTY, "full waiver");
		assertThat(waive.getResponse().getStatus()).isEqualTo(200);
		assertThat(decimal(data(waive), "effective_penalty")).isEqualByComparingTo("0.00");

		// The V3 payment-cap trigger caps a PENALTY allocation at penalty_amount − Σ adjustment: after a full
		// waiver that cap is exactly zero, so the port and the cap formula agree that nothing more is payable.
		BigDecimal v3Cap = jdbc.queryForObject("""
				select i.penalty_amount - coalesce((select sum(a.amount) from penalty_adjustment a
						where a.installment_id = i.id), 0)
				from installment i where i.id = ?
				""", BigDecimal.class, installmentId(1));
		assertThat(v3Cap).isEqualByComparingTo("0.00");
		assertThat(effectiveOf(1)).isEqualByComparingTo(v3Cap);
	}

	@Test
	void anOverWaiverIsRejectedAsConflictAndWritesNothing() throws Exception {
		long adjustmentsBefore = tableCount("penalty_adjustment");
		long waiverEntriesBefore = journalEntryCount("PENALTY_WAIVER");

		MvcResult result = adjust("WAIVE", "5000.00", "over the gross penalty");

		assertThat(result.getResponse().getStatus()).isEqualTo(409);
		assertThat(errorCode(result)).isEqualTo("CONFLICT");
		assertThat(tableCount("penalty_adjustment")).isEqualTo(adjustmentsBefore);
		assertThat(journalEntryCount("PENALTY_WAIVER")).isEqualTo(waiverEntriesBefore);
	}

	@Test
	void anAdjustmentRowIsAppendOnly() throws Exception {
		MvcResult result = adjust("REDUCE", "500.00", "will try to mutate");
		UUID adjustmentId = UUID.fromString(data(result).get("id").asText());

		// V1 trg_penalty_adjustment_immutable (BEFORE UPDATE/DELETE): both raise SQLSTATE P0001.
		assertThat(sqlStateOf(catchThrowable(
				() -> jdbc.update("update penalty_adjustment set amount = 1.00 where id = ?", adjustmentId))))
				.isEqualTo("P0001");
		assertThat(sqlStateOf(catchThrowable(
				() -> jdbc.update("delete from penalty_adjustment where id = ?", adjustmentId))))
				.isEqualTo("P0001");
	}

	@Test
	void theV15CapRejectsARawAdjustmentThatExceedsTheGrossPenalty() {
		// A raw INSERT that bypasses the service guard must still be rejected at commit by the V15 deferred
		// cap trigger (Σ adjustment > penalty_amount, invariant 9), SQLSTATE P0001.
		Throwable thrown = catchThrowable(() -> jdbc.update("""
				insert into penalty_adjustment
					(id, installment_id, adjustment_type, amount, reason, approved_by, created_at, updated_at)
				values (gen_random_uuid(), ?, 'WAIVE', 9999.99, 'raw over-waive', ?, clock_timestamp(), clock_timestamp())
				""", installmentId(1), actorId));
		assertThat(sqlStateOf(thrown)).isEqualTo("P0001");
	}

	@Test
	void theEffectivePenaltyPortEqualsTheV3TriggerSubtractionIncludingPaidPenalty() throws Exception {
		adjust("REDUCE", "1200.00", "parity reduction");

		UUID installmentId = installmentId(1);
		EffectivePenaltySnapshot snapshot = effectivePenalty.loadEffectivePenalty(contractId);
		InstallmentEffectivePenalty effective = snapshot.installments().stream()
				.filter(installment -> installment.installmentId().equals(installmentId))
				.findFirst()
				.orElseThrow();

		// The V16 cap uses penalty_amount − Σ penalty_adjustment − Σ PENALTY payment_allocation (same FILTER
		// as V3's PENALTY cap); the port must agree row-for-row. This fixture pays no penalty, so the paid
		// term is 0 and the numeric expectation is unchanged — the point is the expression now matches the
		// paid-aware definition (ADR-019 Context, F2).
		BigDecimal triggerValue = jdbc.queryForObject("""
				select i.penalty_amount
						- coalesce((select sum(a.amount) from penalty_adjustment a
							where a.installment_id = i.id), 0)
						- coalesce((select sum(pa.amount) from payment_allocation pa
							where pa.installment_id = i.id and pa.allocation_type = 'PENALTY'), 0)
				from installment i where i.id = ?
				""", BigDecimal.class, installmentId);
		assertThat(effective.effective()).isEqualByComparingTo(triggerValue);
		assertThat(effective.grossAccrued()).isEqualByComparingTo(GROSS_PENALTY);
		assertThat(effective.adjustment()).isEqualByComparingTo("1200.00");
		assertThat(effective.paidPenalty()).isEqualByComparingTo("0.00");
		assertThat(triggerValue).isEqualByComparingTo("1946.66");
	}

	@Test
	void partialPenaltyPaymentThenWaiverRespectsTheRemainingCapAndKeepsTheReceivableNonNegative()
			throws Exception {
		// F2 (ADR-019 Context, invariant 9): the waiver cap must subtract penalty already PAID. Pay 2,000.00
		// of the 3,146.66 gross penalty (penalty is first in the waterfall, so the whole payment is PENALTY),
		// leaving a remaining effective penalty of 1,146.66 = gross 3,146.66 − adjustments 0 − paid 2,000.00.
		MvcResult payment = pay("partial-penalty-payment", "2000.00");
		assertThat(payment.getResponse().getStatus()).isEqualTo(201);
		assertThat(allocationTypesOf(payment)).containsExactly("PENALTY");
		assertThat(penaltyAllocatedOf(1)).isEqualByComparingTo("2000.00");
		// The port already reflects the paid term: remaining effective is 1,146.66, not the gross.
		assertThat(effectiveOf(1)).isEqualByComparingTo("1146.66");

		// (a) A waiver that exceeds the REMAINING penalty (1,200.00 > 1,146.66) is rejected 409, writes
		// nothing — the old gross-only cap would have allowed it (1,200.00 <= 3,146.66).
		long adjustmentsBefore = tableCount("penalty_adjustment");
		long waiverEntriesBefore = journalEntryCount("PENALTY_WAIVER");
		MvcResult overRemaining = adjust("WAIVE", "1200.00", "exceeds the remaining penalty");
		assertThat(overRemaining.getResponse().getStatus()).isEqualTo(409);
		assertThat(errorCode(overRemaining)).isEqualTo("CONFLICT");
		assertThat(tableCount("penalty_adjustment")).isEqualTo(adjustmentsBefore);
		assertThat(journalEntryCount("PENALTY_WAIVER")).isEqualTo(waiverEntriesBefore);

		// (b) A legal waiver (<= remaining) commits and leaves net PIUTANG_DENDA for the contract >= 0.
		MvcResult legal = adjust("REDUCE", "500.00", "within the remaining penalty");
		assertThat(legal.getResponse().getStatus()).isEqualTo(200);
		assertThat(netPiutangDenda()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
		// Remaining effective now 1,146.66 − 500.00 = 646.66; the port and V16 cap agree.
		assertThat(effectiveOf(1)).isEqualByComparingTo("646.66");

		// (c) A subsequent small PENALTY allocation still commits: the V3 cap is not poisoned, because the
		// adjustment cap already excluded the paid 2,000.00. V3 PENALTY cap = 3,146.66 − 500.00 = 2,646.66,
		// and Σ PENALTY allocation 2,000.00 + 400.00 = 2,400.00 <= 2,646.66.
		MvcResult secondPayment = pay("second-penalty-payment", "400.00");
		assertThat(secondPayment.getResponse().getStatus()).isEqualTo(201);
		assertThat(allocationTypesOf(secondPayment)).containsExactly("PENALTY");
		assertThat(penaltyAllocatedOf(1)).isEqualByComparingTo("2400.00");
		assertThat(netPiutangDenda()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
	}

	@Test
	void financeAndManajemenAreForbiddenAndWriteNothing() throws Exception {
		long adjustmentsBefore = tableCount("penalty_adjustment");
		for (String role : List.of(AppRole.FINANCE.name(), AppRole.MANAJEMEN.name())) {
			MvcResult denied = mockMvc.perform(post("/api/v1/penalty-adjustments")
							.header("Authorization", "Bearer " + TestJwts.forRoles(actorId, role))
							.contentType(MediaType.APPLICATION_JSON)
							.content(requestBody("WAIVE", GROSS_PENALTY, "denied role")))
					.andReturn();
			assertThat(denied.getResponse().getStatus()).isEqualTo(403);
			assertThat(errorCode(denied)).isEqualTo("FORBIDDEN");
		}
		assertThat(tableCount("penalty_adjustment")).isEqualTo(adjustmentsBefore);
	}

	// --- helpers -------------------------------------------------------------------------------------

	private MvcResult adjust(String type, String amount, String reason) throws Exception {
		return mockMvc.perform(post("/api/v1/penalty-adjustments")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(requestBody(type, amount, reason)))
				.andReturn();
	}

	/** Posts a real CASH payment on the fixture business date (penalty is first in the waterfall). */
	private MvcResult pay(String idempotencyKey, String amount) throws Exception {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("contract_id", contractId.toString());
		body.put("amount", new BigDecimal(amount));
		body.put("channel", "CASH");
		return mockMvc.perform(post("/api/v1/payments")
						.header("Authorization", "Bearer " + token)
						.header("Idempotency-Key", idempotencyKey)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(body)))
				.andReturn();
	}

	private List<String> allocationTypesOf(MvcResult paymentResult) throws Exception {
		List<String> types = new java.util.ArrayList<>();
		for (JsonNode allocation : data(paymentResult).get("allocations")) {
			types.add(allocation.get("allocation_type").asText());
		}
		return types;
	}

	/** Σ PENALTY payment allocation for period {@code periodNo}, read straight from the table. */
	private BigDecimal penaltyAllocatedOf(int periodNo) {
		return jdbc.queryForObject("""
				select coalesce(sum(amount), 0) from payment_allocation
				where installment_id = ? and allocation_type = 'PENALTY'
				""", BigDecimal.class, installmentId(periodNo));
	}

	/** Net PIUTANG_DENDA (Σ debit − Σ credit) across every journal line of this contract. */
	private BigDecimal netPiutangDenda() {
		return jdbc.queryForObject("""
				select coalesce(sum(debit - credit), 0) from journal_line
				where contract_id = ? and account_code = 'PIUTANG_DENDA'
				""", BigDecimal.class, contractId);
	}

	private String requestBody(String type, String amount, String reason) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("contract_id", contractId.toString());
		body.put("installment_id", installmentId(1).toString());
		body.put("adjustment_type", type);
		body.put("amount", new BigDecimal(amount));
		body.put("reason", reason);
		return objectMapper.writeValueAsString(body);
	}

	/** Effective penalty of period {@code periodNo} as the port computes it. */
	private BigDecimal effectiveOf(int periodNo) {
		UUID installmentId = installmentId(periodNo);
		return effectivePenalty.loadEffectivePenalty(contractId).installments().stream()
				.filter(installment -> installment.installmentId().equals(installmentId))
				.map(InstallmentEffectivePenalty::effective)
				.findFirst()
				.orElseThrow();
	}

	private UUID installmentId(int periodNo) {
		return jdbc.queryForObject("select id from installment where contract_id = ? and period_no = ?",
				UUID.class, contractId, periodNo);
	}

	private BigDecimal penaltyAmountOf(int periodNo) {
		return jdbc.queryForObject("select penalty_amount from installment where id = ?", BigDecimal.class,
				installmentId(periodNo));
	}

	private int accrualCount(int periodNo) {
		return jdbc.queryForObject("select count(*) from penalty_accrual where installment_id = ?",
				Integer.class, installmentId(periodNo));
	}

	private UUID waiverEntryOf(UUID adjustmentId) {
		return jdbc.queryForObject("select id from journal_entry where ref_type = 'PENALTY_WAIVER' and ref_id = ?",
				UUID.class, adjustmentId);
	}

	private Map<String, BigDecimal> debitsOf(UUID entryId) {
		return sideOf(entryId, "debit");
	}

	private Map<String, BigDecimal> creditsOf(UUID entryId) {
		return sideOf(entryId, "credit");
	}

	private Map<String, BigDecimal> sideOf(UUID entryId, String column) {
		List<Map<String, Object>> rows = jdbc.queryForList("select account_code, " + column
				+ " from journal_line where journal_entry_id = ? and " + column + " > 0 order by account_code",
				entryId);
		Map<String, BigDecimal> side = new LinkedHashMap<>();
		for (Map<String, Object> row : rows) {
			side.put((String) row.get("account_code"), (BigDecimal) row.get(column));
		}
		return side;
	}

	private BigDecimal debitTotal(UUID entryId) {
		return jdbc.queryForObject("select coalesce(sum(debit), 0) from journal_line where journal_entry_id = ?",
				BigDecimal.class, entryId);
	}

	private BigDecimal creditTotal(UUID entryId) {
		return jdbc.queryForObject("select coalesce(sum(credit), 0) from journal_line where journal_entry_id = ?",
				BigDecimal.class, entryId);
	}

	private long journalEntryCount(String refType) {
		return jdbc.queryForObject("select count(*) from journal_entry where ref_type = ?", Long.class, refType);
	}

	private long tableCount(String table) {
		return jdbc.queryForObject("select count(*) from " + table, Long.class);
	}

	private JsonNode data(MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
	}

	private String errorCode(MvcResult result) throws Exception {
		JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
		return body.get("error").get("code").asText();
	}

	private static BigDecimal decimal(JsonNode node, String field) {
		return new BigDecimal(node.get(field).asText());
	}

	/** Walk the cause chain to the originating {@link SQLException} and return its SQLSTATE. */
	private static String sqlStateOf(Throwable thrown) {
		assertThat(thrown).as("an exception was expected").isNotNull();
		for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
			if (cause instanceof SQLException sqlException) {
				return sqlException.getSQLState();
			}
		}
		throw new AssertionError("no SQLException in the cause chain of " + thrown, thrown);
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
