package com.serfira.contract;

import com.serfira.TestcontainersConfiguration;
import com.serfira.contract.api.ContractResponse;
import com.serfira.contract.api.CreateAssetRequest;
import com.serfira.contract.api.CreateContractRequest;
import com.serfira.contract.api.CreateCustomerRequest;
import com.serfira.contract.application.ContractCommandService;
import com.serfira.contract.application.ContractReceivableSnapshot;
import com.serfira.contract.application.InstallmentBillingPort;
import com.serfira.contract.application.InstallmentReceivable;
import com.serfira.contract.application.InstallmentReceivablePort;
import com.serfira.contract.domain.AssetType;
import com.serfira.contract.domain.InterestScheme;
import com.serfira.penalty.application.EffectivePenaltyPort;
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
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * T30 — the two {@code contract} read paths (schedule row and aging report) report the effective
 * (adjustment-and-paid-aware) penalty, not gross, so a cleared waiver removes an installment from both views
 * and all three contract reads agree with the payment-receivable snapshot (ADR-019 D1/D4). The suite covers
 * three distinct cases: a clearing waiver on an ACTIVE contract, a PARTIAL penalty payment with no waiver
 * (the F1 regression — the schedule must report {@code gross − paid penalty}, not gross), and a closed
 * contract with a prior waiver (the F2 regression — a closed contract's schedule must still net the
 * adjustment, not report gross).
 *
 * <p>Every request commits for real (MockMvc, no test transaction) so the deferred V3/V16 triggers run as in
 * production. Penalty is accrued through the real D1 path (bill then accrue), never by seeding
 * {@code penalty_amount}. Period 1 is made otherwise-paid with test-only SQL (principal + interest covered),
 * leaving the penalty as the sole outstanding amount; a subsequent real {@code POST /api/v1/payments} then
 * allocates to PENALTY first, so a partial payment leaves a reachable penalty remainder with no waiver.
 */
@Import({TestcontainersConfiguration.class, EffectivePenaltyReadConsistencyIT.FixedClockConfig.class})
@SpringBootTest
@AutoConfigureMockMvc
class EffectivePenaltyReadConsistencyIT {

	private static final UUID SYSTEM_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

	/** Period 1 is due 2026-02-28; on 2026-03-05 two late days (03-04, 03-05) fall outside the 3-day grace. */
	private static final LocalDate TODAY = LocalDate.of(2026, 3, 5);

	/** Two charged days at 1,573.33/day = gross recognized penalty on period 1. */
	private static final String GROSS_PENALTY = "3146.66";

	@TestConfiguration(proxyBeanMethods = false)
	static class FixedClockConfig {

		@Bean
		@Primary
		Clock fixedClock() {
			return new FixedClock(TODAY);
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
	InstallmentReceivablePort receivable;

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
					values (?, 'test-hash', 'T30 IT Actor', 'ADMIN_OPERASIONAL', TRUE,
						clock_timestamp(), clock_timestamp())
					returning id
				""", UUID.class, "t30-it-actor-" + UUID.randomUUID());
		token = TestJwts.forRoles(actorId, AppRole.ADMIN_OPERASIONAL.name());

		ContractResponse draft = contracts.create("t30-it-contract-" + UUID.randomUUID(),
				new CreateContractRequest(
						new CreateCustomerRequest("Budi Santoso", "3171012501900001", "08123456789", "Jakarta"),
						new CreateAssetRequest(AssetType.MOTORCYCLE, "Honda", "Beat", null, "B1234XY"),
						new BigDecimal("20000000.00"), new BigDecimal("4000000.00"), 12, InterestScheme.FLAT,
						new BigDecimal("0.0150"), LocalDate.of(2026, 1, 31)));
		contracts.activate(draft.id(), null);
		contractId = draft.id();

		// Real D1 path: bill the due interest, then accrue the two late days so period 1 carries gross
		// penalty 3,146.66 (no seeding of penalty_amount).
		billing.billDueInterest(contractId, TODAY);
		penalty.accrueDuePenalty(contractId, TODAY);
		assertThat(penaltyAmountOf(1)).isEqualByComparingTo(GROSS_PENALTY);
	}

	@AfterEach
	void leaveCleanSharedDatabase() {
		truncateDomainTables();
	}

	@Test
	void aClearingWaiverRemovesPeriodOneFromScheduleAgingAndTheReceivableSnapshotAlike() throws Exception {
		// Period 1: otherwise-paid (principal + recognized interest covered), leaving only the gross penalty
		// outstanding, due 2026-02-28 so on TODAY it sits two days past the 3-day grace → the 1-30 bucket
		// while any penalty remains.
		seedPeriodOnePaidExceptPenalty();
		// Period 2: a small principal residual due a month out (DPD < 0 → the Current bucket, never 1-30). It
		// keeps the contract ACTIVE after the waiver so the receivable snapshot (ACTIVE-only) and the port
		// stay readable, and it isolates period 1's effect in the 1-30 bucket. Periods 3-12 fully resolved.
		LocalDate futureDue = TODAY.plusMonths(1);
		jdbc.update("""
				update installment
					set due_date = ?, interest_amount = 0, recognized_interest_amount = 0, penalty_amount = 0,
						paid_amount = 0, settled_amount = 0, written_off_amount = 0, status = 'PENDING'
					where contract_id = ? and period_no = 2
				""", futureDue, contractId);
		for (int period = 3; period <= 12; period++) {
			zeroOutInstallment(period);
		}

		// Control (before the waiver): period 1's gross penalty is its sole outstanding amount, so the
		// schedule row, period 1's 1-30 aging bucket and the receivable snapshot all report it. The OLD
		// zero-adjustment read would keep reporting this gross value after a waiver; the asserts after the
		// waiver prove the routing now nets the adjustment instead.
		assertThat(scheduleRow(1).get("penalty_amount").decimalValue()).isEqualByComparingTo(GROSS_PENALTY);
		assertThat(scheduleRow(1).get("outstanding").decimalValue()).isEqualByComparingTo(GROSS_PENALTY);
		assertThat(contractAgingBucket("dpd_1_30")).isEqualByComparingTo(GROSS_PENALTY);
		assertThat(snapshotEffectivePenalty(1)).isEqualByComparingTo(GROSS_PENALTY);

		// Clear the whole remaining penalty with a full WAIVE as ADMIN_OPERASIONAL.
		MvcResult waive = adjust("WAIVE", GROSS_PENALTY, "clear the accrued penalty");
		assertThat(waive.getResponse().getStatus()).isEqualTo(200);
		assertThat(decimal(data(waive), "effective_penalty")).isEqualByComparingTo("0.00");
		// The contract stays ACTIVE (period 2 still owes principal), so every read path remains exercised.
		assertThat(contractStatus()).isEqualTo("ACTIVE");

		// (a) The schedule row now shows zero remaining penalty and zero outstanding for period 1.
		JsonNode row = scheduleRow(1);
		assertThat(row.get("penalty_amount").decimalValue()).isEqualByComparingTo("0.00");
		assertThat(row.get("outstanding").decimalValue()).isEqualByComparingTo("0.00");

		// (b) The aging report shows period 1 gone from its 1-30 bucket: that bucket is now zero (period 2's
		// future residual only ever sits in Current, so it cannot mask this).
		assertThat(contractAgingBucket("dpd_1_30")).isEqualByComparingTo("0.00");

		// (c) The receivable snapshot agrees: period 1's effective remaining penalty is zero.
		assertThat(snapshotEffectivePenalty(1)).isEqualByComparingTo("0.00");
	}

	@Test
	void aPartialPenaltyPaymentWithNoWaiverLeavesTheScheduleReportingTheRemainingPenaltyNotGross() throws Exception {
		// Period 1: otherwise-paid (principal + recognized interest covered), leaving only the gross penalty
		// outstanding. No waiver is applied, so Σ adjustment stays zero — the F1 bug (gross − adjustment)
		// would therefore keep reporting GROSS even though a payment has partly cleared the penalty.
		seedPeriodOnePaidExceptPenalty();

		// A real payment of less than the gross penalty: the waterfall (PENALTY first) applies it entirely to
		// the penalty, leaving gross − paid still owed, with no waiver.
		String partialPenaltyPayment = "1000.00";
		MvcResult payment = pay("f1-partial-penalty", partialPenaltyPayment);
		assertThat(payment.getResponse().getStatus()).isEqualTo(201);
		JsonNode paymentData = objectMapper.readTree(payment.getResponse().getContentAsString()).get("data");
		assertThat(allocationTypes(paymentData)).containsExactly("PENALTY");
		assertThat(paymentData.get("allocations").get(0).get("amount").decimalValue())
				.isEqualByComparingTo(partialPenaltyPayment);

		// The port is the single source of the remaining penalty: effective = gross − 0 − paidPenalty.
		BigDecimal portEffective = portEffectivePenalty(1);
		BigDecimal gross = new BigDecimal(GROSS_PENALTY);
		assertThat(portEffective).isEqualByComparingTo(gross.subtract(new BigDecimal(partialPenaltyPayment)));
		// Guard the test's own premise: this is strictly less than gross and the adjustment is zero, so the
		// old gross − adjustment formula would have reported gross and hidden the bug.
		assertThat(portEffective).isLessThan(gross);

		// The schedule row's penalty_amount must equal the port's remaining penalty, NOT gross and NOT
		// gross − adjustment (which both equal gross here).
		JsonNode row = scheduleRow(1);
		assertThat(row.get("penalty_amount").decimalValue()).isEqualByComparingTo(portEffective);
		assertThat(row.get("penalty_amount").decimalValue()).isNotEqualByComparingTo(gross);
	}

	@Test
	void aClosedContractWithAPriorWaiverReportsTheNetPenaltyNotGross() throws Exception {
		// Period 1: otherwise-paid, only the gross penalty outstanding. Apply a partial WAIVE, then close the
		// contract. The F2 bug defaulted a non-ACTIVE contract's adjustments to zero, so a closed contract's
		// schedule reported GROSS penalty again for the previously-waived installment.
		seedPeriodOnePaidExceptPenalty();
		String waived = "1000.00";
		MvcResult waive = adjust("WAIVE", waived, "partial waiver before close");
		assertThat(waive.getResponse().getStatus()).isEqualTo(200);

		BigDecimal expectedNet = new BigDecimal(GROSS_PENALTY).subtract(new BigDecimal(waived));

		// Close the contract (SETTLEMENT), the same test-only status write the 200-stays test uses.
		jdbc.update("update contract set status = 'CLOSED', closed_at = clock_timestamp(), "
				+ "closed_reason = 'SETTLEMENT' where id = ?", contractId);
		assertThat(contractStatus()).isEqualTo("CLOSED");

		// The schedule still returns 200 (no 409 leak) and reports the net remaining penalty, not gross.
		JsonNode row = scheduleRow(1);
		assertThat(row.get("penalty_amount").decimalValue()).isEqualByComparingTo(expectedNet);
		assertThat(row.get("penalty_amount").decimalValue()).isNotEqualByComparingTo(GROSS_PENALTY);
	}

	@Test
	void theSchedulePathStays200ForDraftAndClosedContracts() throws Exception {
		// A DRAFT contract has no schedule yet: 200 with an empty list, not a 409 from the effective-penalty
		// port (which only answers for ACTIVE contracts).
		UUID draft = contracts.create("t30-draft-" + UUID.randomUUID(),
				new CreateContractRequest(
						new CreateCustomerRequest("Siti Aminah", "3171012501900002", "08123456780", "Jakarta"),
						new CreateAssetRequest(AssetType.MOTORCYCLE, "Yamaha", "Mio", null, "B2222BB"),
						new BigDecimal("20000000.00"), new BigDecimal("4000000.00"), 12, InterestScheme.FLAT,
						new BigDecimal("0.0150"), LocalDate.of(2026, 1, 31))).id();
		MvcResult draftSchedule = getSchedule(draft);
		assertThat(draftSchedule.getResponse().getStatus()).isEqualTo(200);
		assertThat(data(draftSchedule)).isEmpty();

		// A CLOSED contract has a schedule but is no longer ACTIVE: still 200, routing through the port did
		// not turn a valid read into a 409 CONTRACT_STATE_INVALID.
		jdbc.update("update contract set status = 'CLOSED', closed_at = clock_timestamp(), "
				+ "closed_reason = 'SETTLEMENT' where id = ?", contractId);
		MvcResult closedSchedule = getSchedule(contractId);
		assertThat(closedSchedule.getResponse().getStatus()).isEqualTo(200);
		assertThat(data(closedSchedule)).isNotEmpty();
	}

	// --- helpers -------------------------------------------------------------------------------------

	/** Seed period 1 so principal + recognized interest are paid, leaving only the gross penalty outstanding. */
	private void seedPeriodOnePaidExceptPenalty() {
		jdbc.update("""
				update installment
					set paid_amount = principal_amount + recognized_interest_amount,
						status = 'PARTIALLY_PAID', paid_at = clock_timestamp()
					where contract_id = ? and period_no = 1
				""", contractId);
	}

	/** Fully resolve an installment so it carries zero outstanding and never contributes. */
	private void zeroOutInstallment(int periodNo) {
		jdbc.update("""
				update installment
					set interest_amount = 0, recognized_interest_amount = 0, penalty_amount = 0,
						paid_amount = principal_amount, settled_amount = 0, written_off_amount = 0,
						status = 'PAID', paid_at = clock_timestamp()
					where contract_id = ? and period_no = ?
				""", contractId, periodNo);
	}

	private MvcResult adjust(String type, String amount, String reason) throws Exception {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("contract_id", contractId.toString());
		body.put("installment_id", installmentId(1).toString());
		body.put("adjustment_type", type);
		body.put("amount", new BigDecimal(amount));
		body.put("reason", reason);
		return mockMvc.perform(post("/api/v1/penalty-adjustments")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(body)))
				.andReturn();
	}

	/** The period's row from GET /api/v1/contracts/{id}/installments. */
	private JsonNode scheduleRow(int periodNo) throws Exception {
		JsonNode rows = data(getSchedule(contractId));
		for (JsonNode row : rows) {
			if (row.get("period_no").asInt() == periodNo) {
				return row;
			}
		}
		throw new AssertionError("period " + periodNo + " not in the schedule response");
	}

	private MvcResult getSchedule(UUID id) throws Exception {
		return mockMvc.perform(get("/api/v1/contracts/" + id + "/installments")
				.header("Authorization", "Bearer " + token)).andReturn();
	}

	/** The named bucket amount for this contract in the aging report, or zero if the contract is absent. */
	private BigDecimal contractAgingBucket(String bucket) throws Exception {
		for (JsonNode row : agingReport().get("contracts").get("content")) {
			if (row.get("contract_id").asText().equals(contractId.toString())) {
				return row.get("buckets").get(bucket).decimalValue();
			}
		}
		return BigDecimal.ZERO.setScale(2);
	}

	private JsonNode agingReport() throws Exception {
		MvcResult result = mockMvc.perform(get("/api/v1/reports/aging")
				.header("Authorization", "Bearer " + token)).andReturn();
		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		return data(result);
	}

	/** Posts a real CASH payment through the HTTP path, so billing, accrual and allocation all run. */
	private MvcResult pay(String idempotencyKey, String amount) throws Exception {
		String body = "{\"contract_id\":\"" + contractId + "\",\"amount\":" + amount + ",\"channel\":\"CASH\"}";
		return mockMvc.perform(post("/api/v1/payments")
						.header("Authorization", "Bearer " + token)
						.header("Idempotency-Key", idempotencyKey)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andReturn();
	}

	private java.util.List<String> allocationTypes(JsonNode payment) {
		java.util.List<String> types = new java.util.ArrayList<>();
		payment.get("allocations").forEach(allocation -> types.add(allocation.get("allocation_type").asText()));
		return types;
	}

	/** The authoritative remaining penalty of the period from the {@code penalty}-owned port. */
	private BigDecimal portEffectivePenalty(int periodNo) {
		UUID installmentId = installmentId(periodNo);
		return effectivePenalty.loadEffectivePenalty(contractId).installments().stream()
				.filter(installment -> installment.installmentId().equals(installmentId))
				.map(InstallmentEffectivePenalty::effective)
				.findFirst()
				.orElseThrow();
	}

	/** Effective remaining penalty of the period from the payment-receivable snapshot: max(0, gross − Σ adj). */
	private BigDecimal snapshotEffectivePenalty(int periodNo) {
		UUID installmentId = installmentId(periodNo);
		ContractReceivableSnapshot snapshot = receivable.loadReceivableSnapshot(contractId);
		InstallmentReceivable row = snapshot.installments().stream()
				.filter(installment -> installment.installmentId().equals(installmentId))
				.findFirst()
				.orElseThrow();
		BigDecimal effective = row.penaltyAmount().subtract(row.penaltyAdjustments());
		return effective.signum() < 0 ? BigDecimal.ZERO.setScale(2) : effective;
	}

	private UUID installmentId(int periodNo) {
		return jdbc.queryForObject("select id from installment where contract_id = ? and period_no = ?",
				UUID.class, contractId, periodNo);
	}

	private BigDecimal penaltyAmountOf(int periodNo) {
		return jdbc.queryForObject("select penalty_amount from installment where id = ?", BigDecimal.class,
				installmentId(periodNo));
	}

	private String contractStatus() {
		return jdbc.queryForObject("select status from contract where id = ?", String.class, contractId);
	}

	private JsonNode data(MvcResult result) throws Exception {
		assertThat(result.getResponse().getStatus())
				.as("response %s: %s", result.getResponse().getStatus(), result.getResponse().getContentAsString())
				.isEqualTo(200);
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
	}

	private static BigDecimal decimal(JsonNode node, String field) {
		return new BigDecimal(node.get(field).asText());
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
