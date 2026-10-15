package com.serfira.contract;

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
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * F5 — contract write-off end to end (task T19, DM §1.3/§1.12): {@code POST
 * /api/v1/contracts/{id}/write-off} accrues before resolve, caps the recognized receivable, moves open
 * installments to {@code WRITTEN_OFF}, closes the contract {@code TERMINATED}, and posts one balanced
 * {@code WRITE_OFF} journal. Real commits (MockMvc, no test transaction) so the V4/V19 deferred CHECKs and
 * the V3 amount triggers fire exactly where they do in production.
 */
@Import({TestcontainersConfiguration.class, WriteOffClockTestConfiguration.class})
@SpringBootTest
@AutoConfigureMockMvc
class ContractWriteOffIT {

	private static final UUID SYSTEM_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

	private static final String ADMIN = "ADMIN_OPERASIONAL";

	/** A date on which periods 1 and 2 are overdue (start 2026-01-31; due 02-28, 03-31; grace 3 days). */
	private static final LocalDate OVERDUE_DATE = LocalDate.of(2026, 4, 15);

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
					values (?, 'test-hash', 'Write-off IT Actor', 'ADMIN_OPERASIONAL', TRUE,
						clock_timestamp(), clock_timestamp())
					returning id""", UUID.class, "writeoff-it-actor-" + UUID.randomUUID());
		adminToken = TestJwts.forRoles(actorId, ADMIN);
		fixedClock().setDate(WriteOffClockTestConfiguration.BUSINESS_DATE);

		ContractResponse draft = contracts.create("writeoff-it-contract-" + UUID.randomUUID(),
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
	void writingOffAnOverdueContractTerminatesItCapsReceivableAndPostsOneBalancedEntry() throws Exception {
		fixedClock().setDate(OVERDUE_DATE);

		MvcResult result = writeOff("wo-happy", "{\"reason\":\"collection decision CD-42\"}");

		assertThat(result.getResponse().getStatus()).isEqualTo(201);
		JsonNode data = data(result);
		assertThat(data.get("status").asText()).isEqualTo("TERMINATED");
		assertThat(data.get("write_off_reason").asText()).isEqualTo("collection decision CD-42");
		assertThat(data.get("installments")).isNotEmpty();

		// Contract closed TERMINATED with the authenticated actor (never a body value).
		Map<String, Object> contract = jdbc.queryForMap(
				"select status, write_off_reason, write_off_recorded_by from contract where id = ?", contractId);
		assertThat(contract.get("status")).isEqualTo("TERMINATED");
		assertThat(contract.get("write_off_reason")).isEqualTo("collection decision CD-42");
		assertThat(contract.get("write_off_recorded_by")).isEqualTo(actorId);

		// Every installment is WRITTEN_OFF, and every one has zero outstanding (receivable capped).
		assertThat(installmentStatuses()).containsOnly("WRITTEN_OFF");
		assertThat(jdbc.queryForObject("""
				select count(*) from installment where contract_id = ?
					and (principal_amount + recognized_interest_amount + penalty_amount)
						- (paid_amount + settled_amount + written_off_amount) <> 0
				""", Long.class, contractId)).isZero();

		// Exactly one balanced WRITE_OFF entry: Dr BIAYA_PENGHAPUSAN_PIUTANG, Cr the receivable accounts.
		assertThat(journalEntryCount("WRITE_OFF")).isEqualTo(1L);
		UUID entry = onlyEntry("WRITE_OFF", contractId);
		Map<String, BigDecimal> debits = debitByAccount(entry);
		assertThat(debits).containsOnlyKeys("BIAYA_PENGHAPUSAN_PIUTANG");
		assertThat(jdbc.queryForObject(
				"select sum(debit) from journal_line where journal_entry_id = ?", BigDecimal.class, entry))
				.isEqualByComparingTo(jdbc.queryForObject(
						"select sum(credit) from journal_line where journal_entry_id = ?", BigDecimal.class, entry));
		assertThat(jdbc.queryForObject("""
				select count(*) from journal_line
				where journal_entry_id = ? and credit > 0
					and account_code not in ('PIUTANG_DENDA','PIUTANG_BUNGA','PIUTANG_POKOK')
				""", Long.class, entry)).isZero();
		// Penalty was accrued before resolve (periods overdue), so a PIUTANG_DENDA credit line exists.
		assertThat(creditByAccount(entry)).containsKey("PIUTANG_DENDA");
	}

	@Test
	void anIdenticalRetryReplaysAndDoesNotWriteOffTwice() throws Exception {
		fixedClock().setDate(OVERDUE_DATE);

		MvcResult first = writeOff("wo-replay", "{\"reason\":\"CD-99\"}");
		assertThat(first.getResponse().getStatus()).isEqualTo(201);
		MvcResult replay = writeOff("wo-replay", "{\"reason\":\"CD-99\"}");
		assertThat(replay.getResponse().getStatus()).isEqualTo(201);

		// One WRITE_OFF entry only; the replay returned the stored response.
		assertThat(journalEntryCount("WRITE_OFF")).isEqualTo(1L);
	}

	@Test
	void writingOffANonActiveContractIsRejectedAndWritesNothing() throws Exception {
		fixedClock().setDate(OVERDUE_DATE);
		assertThat(writeOff("wo-first", "{\"reason\":\"CD-1\"}").getResponse().getStatus()).isEqualTo(201);
		long writeOffEntriesBefore = journalEntryCount("WRITE_OFF");

		// The contract is now TERMINATED: a second write-off (fresh key) is rejected and posts nothing.
		MvcResult second = writeOff("wo-second", "{\"reason\":\"CD-2\"}");

		assertThat(second.getResponse().getStatus()).isEqualTo(409);
		assertThat(errorCode(second)).isEqualTo("CONTRACT_STATE_INVALID");
		assertThat(journalEntryCount("WRITE_OFF")).isEqualTo(writeOffEntriesBefore);
	}

	@Test
	void aBlankReasonIsAValidationErrorAndWritesNothing() throws Exception {
		fixedClock().setDate(OVERDUE_DATE);

		MvcResult result = writeOff("wo-blank", "{\"reason\":\"   \"}");

		assertThat(result.getResponse().getStatus()).isEqualTo(400);
		assertThat(errorCode(result)).isEqualTo("VALIDATION_ERROR");
		assertThat(journalEntryCount("WRITE_OFF")).isZero();
		assertThat(statusOf(contractId)).isEqualTo("ACTIVE");
	}

	@Test
	void aMissingIdempotencyKeyIsAValidationError() throws Exception {
		fixedClock().setDate(OVERDUE_DATE);

		MvcResult result = mockMvc.perform(post("/api/v1/contracts/" + contractId + "/write-off")
						.header("Authorization", "Bearer " + adminToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"reason\":\"CD-1\"}"))
				.andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(400);
		assertThat(errorCode(result)).isEqualTo("VALIDATION_ERROR");
		assertThat(statusOf(contractId)).isEqualTo("ACTIVE");
	}

	@Test
	void writingOffAnUnknownContractIsNotFound() throws Exception {
		MvcResult result = mockMvc.perform(post("/api/v1/contracts/" + UUID.randomUUID() + "/write-off")
						.header("Authorization", "Bearer " + adminToken)
						.header("Idempotency-Key", "wo-unknown")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"reason\":\"CD-1\"}"))
				.andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(404);
		assertThat(errorCode(result)).isEqualTo("CONTRACT_NOT_FOUND");
	}

	@Test
	void theV19CheckRejectsATerminatedContractWithoutARecordingActorEvenViaRawSql() {
		// A TERMINATED contract must carry write_off_recorded_by (V19). Forcing one in via raw SQL without
		// the actor is rejected by the strengthened ck_contract_terminated_coherence CHECK.
		assertThatThrownBy(() -> jdbc.update("""
				update contract set status = 'TERMINATED', write_off_reason = 'raw sql',
					write_off_recorded_by = null, closed_at = clock_timestamp()
				where id = ?
				""", contractId))
				.isInstanceOf(DataAccessException.class)
				.hasMessageContaining("ck_contract_terminated_coherence");

		assertThat(statusOf(contractId)).isEqualTo("ACTIVE");
	}

	// --- helpers ---------------------------------------------------------------------------------------

	private MvcResult writeOff(String idempotencyKey, String jsonBody) throws Exception {
		return mockMvc.perform(post("/api/v1/contracts/" + contractId + "/write-off")
						.header("Authorization", "Bearer " + adminToken)
						.header("Idempotency-Key", idempotencyKey)
						.contentType(MediaType.APPLICATION_JSON)
						.content(jsonBody))
				.andReturn();
	}

	private JsonNode data(MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
	}

	private String errorCode(MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString())
				.get("error").get("code").asText();
	}

	private String statusOf(UUID id) {
		return jdbc.queryForObject("select status from contract where id = ?", String.class, id);
	}

	private List<String> installmentStatuses() {
		return jdbc.queryForList("select status from installment where contract_id = ?", String.class, contractId);
	}

	private long journalEntryCount(String refType) {
		return jdbc.queryForObject("select count(*) from journal_entry where ref_type = ?", Long.class, refType);
	}

	private UUID onlyEntry(String refType, UUID refId) {
		return jdbc.queryForObject("select id from journal_entry where ref_type = ? and ref_id = ?",
				UUID.class, refType, refId);
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
