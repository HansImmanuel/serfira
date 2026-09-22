package com.serfira.contract;

import com.serfira.TestcontainersConfiguration;
import com.serfira.contract.api.ContractResponse;
import com.serfira.contract.api.CreateAssetRequest;
import com.serfira.contract.api.CreateContractRequest;
import com.serfira.contract.api.CreateCustomerRequest;
import com.serfira.contract.application.ContractCommandService;
import com.serfira.contract.application.ContractNotFoundException;
import com.serfira.contract.application.InstallmentBillingPort;
import com.serfira.contract.domain.AssetType;
import com.serfira.contract.domain.ContractStateException;
import com.serfira.contract.domain.InterestScheme;
import com.serfira.shared.clock.Clock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C4 — {@link InstallmentBillingPort} against a real PostgreSQL (Addendum §12, DM §1.4/§1.13, ADR-011):
 * the due-date window, the recognized amounts, the {@code BILLING} journal shape and its {@code entry_date},
 * the idempotence of a repeated run, and the states that must never be billed.
 *
 * <p>Every call commits for real (no test transaction), so the deferred V3 amount invariants and the V4
 * coherence CHECKs are evaluated exactly as in production. The business date is a parameter of the port,
 * so the window is asserted directly instead of by moving a clock; only {@code entry_date} comes from the
 * application clock's business zone.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class InstallmentBillingIT {

	/** Fixture schedule: 12 × FLAT 1.5% from 2026-01-31 → period 1 due 2026-02-28, period 7 due 2026-08-31. */
	private static final LocalDate FIRST_DUE_DATE = LocalDate.of(2026, 2, 28);
	private static final LocalDate SECOND_DUE_DATE = LocalDate.of(2026, 3, 31);
	private static final LocalDate SEVENTH_DUE_DATE = LocalDate.of(2026, 8, 31);
	private static final String PERIOD_INTEREST = "240000.00";
	private static final String PERIOD_PRINCIPAL = "1333333.33";

	@Autowired
	InstallmentBillingPort billing;

	@Autowired
	ContractCommandService commands;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	Clock clock;

	private UUID contractId;

	@BeforeEach
	void createActivatedContract() {
		truncateDomainTables();
		contractId = activateContract("0.0150", 12);
	}

	@AfterEach
	void leaveCleanSharedDatabase() {
		truncateDomainTables();
	}

	@Test
	void billingRecognizesOnlyTheInstallmentsWhoseDueDateHasBeenReached() {
		billing.billDueInterest(contractId, FIRST_DUE_DATE);

		// The window is inclusive: interest is receivable ON its due date (PRD scenario 1).
		assertThat(recognizedOf(1)).isEqualByComparingTo(PERIOD_INTEREST);
		assertThat(recognizedOf(2)).isEqualByComparingTo("0.00");
		assertThat(billingEntryCount()).isEqualTo(1L);

		billing.billDueInterest(contractId, SEVENTH_DUE_DATE);

		// Periods 2–7 became due in the meantime; period 8 (2026-09-30) is still in the future, and
		// period 1 was already billed, so it is not billed twice.
		assertThat(recognizedOf(7)).isEqualByComparingTo(PERIOD_INTEREST);
		assertThat(recognizedOf(8)).isEqualByComparingTo("0.00");
		assertThat(billingEntryCount()).isEqualTo(7L);
		assertThat(statusOf(1)).isEqualTo("PENDING");
		assertThat(paidAmountOf(1)).isEqualByComparingTo("0.00");
	}

	@Test
	void theRecognitionJournalIsTheDocumentedBillingShapeWithTheDueDateAsEntryDate() {
		billing.billDueInterest(contractId, FIRST_DUE_DATE);

		UUID entryId = billingEntryOf(1);
		OffsetDateTime entryDate = entryDateOf(entryId);
		// Accounting date = the installment's due date (DM §1.13): the instant is midnight of that date in
		// business time and the date itself is unchanged when read back in that zone.
		assertThat(entryDate.toInstant()).isEqualTo(FIRST_DUE_DATE.atStartOfDay(clock.zone()).toInstant());
		assertThat(entryDate.atZoneSameInstant(clock.zone()).toLocalDate()).isEqualTo(FIRST_DUE_DATE);
		assertThat(jdbc.queryForObject("select description from journal_entry where id = ?", String.class, entryId))
				.contains("period 1");

		assertThat(debitsOf(entryId)).containsExactly(Map.entry("PIUTANG_BUNGA",
				new BigDecimal(PERIOD_INTEREST)));
		assertThat(creditsOf(entryId)).containsExactly(Map.entry("PENDAPATAN_BUNGA",
				new BigDecimal(PERIOD_INTEREST)));
		// Reconciliation groups receivable against installments by contract (Addendum §7.1 check C).
		assertThat(jdbc.queryForObject("select count(*) from journal_line where journal_entry_id = ? "
				+ "and contract_id <> ?", Long.class, entryId, contractId)).isZero();
	}

	@Test
	void repeatingTheBillingRunRecognizesNothingNew() {
		billing.billDueInterest(contractId, SEVENTH_DUE_DATE);
		BigDecimal recognizedBefore = recognizedOf(1);
		long entriesBefore = billingEntryCount();

		// A second run in its own transaction: the state guard, not just the ledger's (ref_type, ref_id)
		// guard, is what makes the step idempotent.
		billing.billDueInterest(contractId, SEVENTH_DUE_DATE);

		assertThat(recognizedOf(1)).isEqualByComparingTo(recognizedBefore);
		assertThat(recognizedOf(7)).isEqualByComparingTo(PERIOD_INTEREST);
		assertThat(billingEntryCount()).isEqualTo(entriesBefore);
	}

	@Test
	void aZeroInterestContractHasNothingToBill() {
		// A schedule of its own: invariant 18 allows only one live contract per (customer, asset), so the
		// fixture contract has to go before this one is created.
		truncateDomainTables();
		contractId = activateContract("0.0000", 12);

		billing.billDueInterest(contractId, SEVENTH_DUE_DATE);

		// A zero-amount journal line is forbidden (V1 ck_journal_line_not_zero), so a period with no
		// scheduled interest must produce no entry at all instead of an empty one.
		assertThat(billingEntryCount()).isZero();
		assertThat(recognizedOf(1)).isEqualByComparingTo("0.00");
	}

	@Test
	void anInstallmentPaidAtPrincipalOnlyIsStillBilledAndKeepsItsPaidState() {
		// Pre-C4 data: the payment resolved the whole receivable it could see (principal only, because no
		// interest had been billed yet) and left the installment PAID.
		seedPeriod(1, "paid_amount = " + PERIOD_PRINCIPAL + ", status = 'PAID', paid_at = clock_timestamp()");

		billing.billDueInterest(contractId, FIRST_DUE_DATE);

		assertThat(recognizedOf(1)).isEqualByComparingTo(PERIOD_INTEREST);
		// PAID is a cash marker, not a settlement: recognizing the interest must not invent a transition
		// the DM state machine does not have (ADR-011).
		assertThat(statusOf(1)).isEqualTo("PAID");
		assertThat(billingEntryCount()).isEqualTo(1L);
	}

	@Test
	void settledAndWrittenOffInstallmentsAreNeverBilled() {
		seedPeriod(1, "settled_amount = " + PERIOD_PRINCIPAL + ", status = 'SETTLED', "
				+ "settled_at = clock_timestamp()");
		seedPeriod(2, "written_off_amount = " + PERIOD_PRINCIPAL + ", status = 'WRITTEN_OFF', "
				+ "written_off_at = clock_timestamp()");

		billing.billDueInterest(contractId, SEVENTH_DUE_DATE);

		// Both installments are due and would still have a full period of interest to recognize, so only the
		// state rule explains why they were skipped: settlement recognizes its own accrued interest and a
		// write-off caps the recognized receivable (Addendum §12, invariant 15).
		assertThat(recognizedOf(1)).isEqualByComparingTo("0.00");
		assertThat(recognizedOf(2)).isEqualByComparingTo("0.00");
		assertThat(billingEntriesOf(1)).isZero();
		assertThat(billingEntriesOf(2)).isZero();
		assertThat(billingEntryCount()).isEqualTo(5L);
	}

	@Test
	void billingRefusesAContractThatIsNotActiveOrUnknown() {
		ContractResponse draft = commands.create("it-billing-draft-" + UUID.randomUUID(), new CreateContractRequest(
				new CreateCustomerRequest("Siti Aminah", "3171012501900002", "08123456780", "Bandung"),
				new CreateAssetRequest(AssetType.MOTORCYCLE, "Yamaha", "Mio", null, "B9999ZZ"),
				new BigDecimal("20000000.00"), new BigDecimal("4000000.00"), 12, InterestScheme.FLAT,
				new BigDecimal("0.0150"), LocalDate.of(2026, 1, 31)));

		// Only a serviced contract accrues billable interest; a caller asking otherwise is wrong, not noisy.
		assertThatThrownBy(() -> billing.billDueInterest(draft.id(), SEVENTH_DUE_DATE))
				.isInstanceOf(ContractStateException.class);
		assertThatThrownBy(() -> billing.billDueInterest(UUID.randomUUID(), SEVENTH_DUE_DATE))
				.isInstanceOf(ContractNotFoundException.class);
		assertThat(billingEntryCount()).isZero();
	}

	private UUID activateContract(String interestRate, int tenorMonths) {
		ContractResponse draft = commands.create("it-billing-key-" + UUID.randomUUID(), new CreateContractRequest(
				new CreateCustomerRequest("Budi Santoso", "3171012501900001", "08123456789", "Jakarta"),
				new CreateAssetRequest(AssetType.MOTORCYCLE, "Honda", "Beat", null, "B1234XY"),
				new BigDecimal("20000000.00"), new BigDecimal("4000000.00"), tenorMonths, InterestScheme.FLAT,
				new BigDecimal(interestRate), LocalDate.of(2026, 1, 31)));
		commands.activate(draft.id(), null);
		return draft.id();
	}

	private BigDecimal recognizedOf(int periodNo) {
		return jdbc.queryForObject("select recognized_interest_amount from installment "
				+ "where contract_id = ? and period_no = ?", BigDecimal.class, contractId, periodNo);
	}

	private BigDecimal paidAmountOf(int periodNo) {
		return jdbc.queryForObject("select paid_amount from installment where contract_id = ? and period_no = ?",
				BigDecimal.class, contractId, periodNo);
	}

	private String statusOf(int periodNo) {
		return jdbc.queryForObject("select status from installment where contract_id = ? and period_no = ?",
				String.class, contractId, periodNo);
	}

	/** One recognition entry per installment, keyed by the installment it recognizes (ADR-011). */
	private UUID billingEntryOf(int periodNo) {
		return jdbc.queryForObject("select id from journal_entry where ref_type = 'BILLING' and ref_id = "
				+ "(select id from installment where contract_id = ? and period_no = ?)",
				UUID.class, contractId, periodNo);
	}

	private long billingEntriesOf(int periodNo) {
		return jdbc.queryForObject("select count(*) from journal_entry where ref_type = 'BILLING' and ref_id = "
				+ "(select id from installment where contract_id = ? and period_no = ?)",
				Long.class, contractId, periodNo);
	}

	private long billingEntryCount() {
		return jdbc.queryForObject("select count(*) from journal_entry where ref_type = 'BILLING'", Long.class);
	}

	private OffsetDateTime entryDateOf(UUID entryId) {
		return jdbc.queryForObject("select entry_date from journal_entry where id = ?",
				OffsetDateTime.class, entryId);
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
		Map<String, BigDecimal> side = new java.util.LinkedHashMap<>();
		for (Map<String, Object> row : rows) {
			side.put((String) row.get("account_code"), (BigDecimal) row.get(column));
		}
		return side;
	}

	/** Test-only SQL: the states C4 must skip are unreachable through the story's own write paths. */
	private void seedPeriod(int periodNo, String assignments) {
		jdbc.update("update installment set " + assignments + " where contract_id = ? and period_no = ?",
				contractId, periodNo);
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
