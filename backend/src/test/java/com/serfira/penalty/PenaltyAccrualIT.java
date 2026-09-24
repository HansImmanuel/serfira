package com.serfira.penalty;

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
import com.serfira.penalty.application.PenaltyAccrualPort;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D1 — {@link PenaltyAccrualPort} against a real PostgreSQL (TS §4.3, DM §1.9, PRD D-1/D-3, ADR-012): the
 * grace window, the per-day rows, the {@code penalty_amount} they sum to, the {@code PENALTY_ACCRUAL} journal
 * shape and its {@code entry_date}, the idempotence of repeated and backfilled runs, and what happens to the
 * accrual when the base moves.
 *
 * <p>Every call commits for real (no test transaction), so the deferred V3 amount/component invariants are
 * evaluated exactly as in production. The business date is a parameter of the port, so windows are asserted
 * directly instead of by moving a clock; only {@code entry_date} comes from the application clock's business
 * zone. Interest is billed with the real C4 step (never SQL), because the penalty base is "unpaid
 * pokok+bunga" and only billed interest is receivable (PRD §5C).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PenaltyAccrualIT {

	/** Fixture schedule: 12 × FLAT 1.5% from 2026-01-31 → period 1 due 2026-02-28, period 2 due 2026-03-31. */
	private static final LocalDate FIRST_DUE_DATE = LocalDate.of(2026, 2, 28);
	private static final LocalDate FIRST_CHARGED_DAY = LocalDate.of(2026, 3, 4);
	private static final String PERIOD_PRINCIPAL = "1333333.33";
	private static final String PERIOD_INTEREST = "240000.00";
	private static final String PERIOD_TOTAL = "1573333.33";
	private static final String DAY_CHARGE = "1573.33";

	@Autowired
	PenaltyAccrualPort penalty;

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
		contractId = activateContract("0.0150");
	}

	@AfterEach
	void leaveCleanSharedDatabase() {
		truncateDomainTables();
	}

	@Test
	void everyLateDayOutsideTheGraceWindowBecomesOneRowOneJournalAndOneIncrement() {
		billInterest(LocalDate.of(2026, 3, 5));

		int accrued = penalty.accrueDuePenalty(contractId, LocalDate.of(2026, 3, 5));

		assertThat(accrued).isEqualTo(2);
		assertThat(accrualDatesOf(1)).containsExactly(LocalDate.of(2026, 3, 4), LocalDate.of(2026, 3, 5));
		assertThat(daysLateOf(1)).containsExactly(1, 2);
		assertThat(accrualTotalOf(1)).isEqualByComparingTo("3146.66");
		// The rows are the history of the gross value on the installment (DM §1.9, TS §4.3).
		assertThat(penaltyAmountOf(1)).isEqualByComparingTo(accrualTotalOf(1));
		assertThat(journalEntryCount("PENALTY_ACCRUAL")).isEqualTo(2L);
		// Period 2 is due 2026-03-31, so it is not late yet; the number of periods stays untouched.
		assertThat(accrualCount(2)).isZero();
		// D1 recognizes money only: marking OVERDUE is the aging job's decision (story D2).
		assertThat(statusOf(1)).isEqualTo("PENDING");
	}

	@Test
	void theGraceDaysAreFreeAndTheDayAfterIsTheFirstChargedDay() {
		billInterest(LocalDate.of(2026, 3, 4));

		// Three days late is still inside the 3-day grace period: nothing to charge, no journal entry.
		assertThat(penalty.accrueDuePenalty(contractId, LocalDate.of(2026, 3, 3))).isZero();
		assertThat(journalEntryCount("PENALTY_ACCRUAL")).isZero();

		assertThat(penalty.accrueDuePenalty(contractId, FIRST_CHARGED_DAY)).isEqualTo(1);
		assertThat(accrualDatesOf(1)).containsExactly(FIRST_CHARGED_DAY);
		assertThat(daysLateOf(1)).containsExactly(1);
		assertThat(accrualTotalOf(1)).isEqualByComparingTo(DAY_CHARGE);
	}

	@Test
	void theAccrualJournalIsTheDocumentedShapeWithTheLateDayAsEntryDate() {
		billInterest(FIRST_CHARGED_DAY);

		penalty.accrueDuePenalty(contractId, FIRST_CHARGED_DAY);

		UUID entryId = accrualEntryOf(1, FIRST_CHARGED_DAY);
		// Accounting date = the day that was late (DM §1.13), not the moment the step ran: midnight in
		// business time, and the date is unchanged when read back in that zone.
		OffsetDateTime entryDate = entryDateOf(entryId);
		assertThat(entryDate.toInstant()).isEqualTo(FIRST_CHARGED_DAY.atStartOfDay(clock.zone()).toInstant());
		assertThat(entryDate.atZoneSameInstant(clock.zone()).toLocalDate()).isEqualTo(FIRST_CHARGED_DAY);
		assertThat(jdbc.queryForObject("select description from journal_entry where id = ?", String.class, entryId))
				.contains("day 1").contains("period 1");
		// The day itself is the event, so the entry is keyed by the accrual row it booked (ADR-012).
		assertThat(jdbc.queryForObject("select ref_id from journal_entry where id = ?", UUID.class, entryId))
				.isEqualTo(accrualIdOf(1, FIRST_CHARGED_DAY));

		assertThat(debitsOf(entryId)).containsExactly(Map.entry("PIUTANG_DENDA", new BigDecimal(DAY_CHARGE)));
		assertThat(creditsOf(entryId)).containsExactly(Map.entry("PENDAPATAN_DENDA", new BigDecimal(DAY_CHARGE)));
		// Reconciliation groups receivable against installments by contract (Addendum §7.1 check C).
		assertThat(jdbc.queryForObject("select count(*) from journal_line where journal_entry_id = ? "
				+ "and contract_id <> ?", Long.class, entryId, contractId)).isZero();
	}

	@Test
	void repeatingTheRunForTheSameBusinessDateAccruesNothing() {
		billInterest(LocalDate.of(2026, 3, 5));
		penalty.accrueDuePenalty(contractId, LocalDate.of(2026, 3, 5));
		BigDecimal accruedBefore = accrualTotalOf(1);
		long entriesBefore = journalEntryCount("PENALTY_ACCRUAL");

		// A second run in its own transaction: the accrual rows and their unique (installment, date) key are
		// what makes the step idempotent, not an in-memory flag.
		assertThat(penalty.accrueDuePenalty(contractId, LocalDate.of(2026, 3, 5))).isZero();

		assertThat(accrualTotalOf(1)).isEqualByComparingTo(accruedBefore);
		assertThat(journalEntryCount("PENALTY_ACCRUAL")).isEqualTo(entriesBefore);
	}

	@Test
	void aLaterRunChargesOnlyTheDaysThatAreStillMissing() {
		billInterest(LocalDate.of(2026, 3, 7));
		penalty.accrueDuePenalty(contractId, LocalDate.of(2026, 3, 5));

		assertThat(penalty.accrueDuePenalty(contractId, LocalDate.of(2026, 3, 7))).isEqualTo(2);

		assertThat(accrualDatesOf(1)).containsExactly(LocalDate.of(2026, 3, 4), LocalDate.of(2026, 3, 5),
				LocalDate.of(2026, 3, 6), LocalDate.of(2026, 3, 7));
		assertThat(penaltyAmountOf(1)).isEqualByComparingTo("6293.32");
		assertThat(journalEntryCount("PENALTY_ACCRUAL")).isEqualTo(4L);
	}

	@Test
	void aBackfillRunForAnEarlierBusinessDateChargesNothingNew() {
		billInterest(LocalDate.of(2026, 3, 7));
		penalty.accrueDuePenalty(contractId, LocalDate.of(2026, 3, 7));
		BigDecimal accruedBefore = accrualTotalOf(1);

		// The window is per date, so re-running for a date inside the already-charged range adds nothing.
		assertThat(penalty.accrueDuePenalty(contractId, LocalDate.of(2026, 3, 5))).isZero();

		assertThat(accrualTotalOf(1)).isEqualByComparingTo(accruedBefore);
		assertThat(accrualCount(1)).isEqualTo(4);
	}

	@Test
	void aPartialPaymentLowersTheBaseWithoutStoppingLaterDailyAccruals() {
		billInterest(LocalDate.of(2026, 3, 5));
		penalty.accrueDuePenalty(contractId, LocalDate.of(2026, 3, 5));
		// Test-only SQL: the payment write path is C3; here only the installment's resolved amount has to
		// move so that the base of the following days drops to 1,573,333.33 − 1,500,000.00 = 73,333.33.
		seedPeriod(1, "paid_amount = 1500000.00, status = 'PARTIALLY_PAID', paid_at = clock_timestamp()");

		assertThat(penalty.accrueDuePenalty(contractId, LocalDate.of(2026, 3, 6))).isEqualTo(1);

		// The day still accrues, at 73.33 — the cumulative-expected rule rejected in ADR-012 alternative 3
		// would have recognized nothing here and for weeks afterwards.
		assertThat(accrualAmountOf(1, LocalDate.of(2026, 3, 6))).isEqualByComparingTo("73.33");
		assertThat(accrualDatesOf(1)).containsExactly(LocalDate.of(2026, 3, 4), LocalDate.of(2026, 3, 5),
				LocalDate.of(2026, 3, 6));
		assertThat(penaltyAmountOf(1)).isEqualByComparingTo("3219.99");
	}

	@Test
	void aFullyResolvedInstallmentHasNothingLeftToCharge() {
		billInterest(LocalDate.of(2026, 3, 5));
		penalty.accrueDuePenalty(contractId, LocalDate.of(2026, 3, 5));
		// The waterfall pays denda first, so the payment that closes the installment covers it too:
		// 1,333,333.33 principal + 240,000.00 interest + 3,146.66 denda.
		seedPeriod(1, "paid_amount = 1576479.99, status = 'PAID', paid_at = clock_timestamp()");

		assertThat(penalty.accrueDuePenalty(contractId, LocalDate.of(2026, 3, 10))).isZero();

		assertThat(accrualCount(1)).isEqualTo(2);
		assertThat(penaltyAmountOf(1)).isEqualByComparingTo("3146.66");
	}

	@Test
	void settledAndWrittenOffInstallmentsAreNeverCharged() {
		billInterest(LocalDate.of(2026, 3, 31));
		seedPeriod(1, "settled_amount = " + PERIOD_PRINCIPAL + ", status = 'SETTLED', "
				+ "settled_at = clock_timestamp()");
		seedPeriod(2, "written_off_amount = " + PERIOD_PRINCIPAL + ", status = 'WRITTEN_OFF', "
				+ "written_off_at = clock_timestamp()");

		// Both days are late on 2026-04-10 and period 1 still owes 240,000.00 of billed interest, so only the
		// state rule explains why nothing was charged: settlement settles the denda outstanding and a
		// write-off caps the recognized receivable (invariant 15, ADR-012).
		assertThat(unpaidPrincipalAndInterestOf(1)).isEqualByComparingTo(PERIOD_INTEREST);
		assertThat(penalty.accrueDuePenalty(contractId, LocalDate.of(2026, 4, 10))).isZero();

		assertThat(accrualCount(1)).isZero();
		assertThat(accrualCount(2)).isZero();
		assertThat(journalEntryCount("PENALTY_ACCRUAL")).isZero();
	}

	@Test
	void anInstallmentResolvedAtPrincipalOnlyAccruesOnItsBilledInterest() {
		seedPeriod(1, "paid_amount = " + PERIOD_PRINCIPAL + ", status = 'PAID', paid_at = clock_timestamp()");

		// C4 bills a PAID installment's interest anyway (ADR-011 decision 5), and because PAID is a cash
		// marker rather than "nothing owed", that receivable is still a penalty base (ADR-012 decision 2).
		billInterest(FIRST_CHARGED_DAY);
		assertThat(penalty.accrueDuePenalty(contractId, FIRST_CHARGED_DAY)).isEqualTo(1);

		assertThat(accrualAmountOf(1, FIRST_CHARGED_DAY)).isEqualByComparingTo("240.00");
		assertThat(penaltyAmountOf(1)).isEqualByComparingTo("240.00");
	}

	@Test
	void anUnknownOrNonActiveContractIsRejected() {
		ContractResponse draft = commands.create("it-penalty-draft-" + UUID.randomUUID(), new CreateContractRequest(
				new CreateCustomerRequest("Siti Aminah", "3171012501900002", "08123456780", "Bandung"),
				new CreateAssetRequest(AssetType.MOTORCYCLE, "Yamaha", "Mio", null, "B9999ZZ"),
				new BigDecimal("20000000.00"), new BigDecimal("4000000.00"), 12, InterestScheme.FLAT,
				new BigDecimal("0.0150"), LocalDate.of(2026, 1, 31)));

		// Only a serviced contract accrues; a caller asking otherwise is wrong, not noisy.
		assertThatThrownBy(() -> penalty.accrueDuePenalty(draft.id(), FIRST_CHARGED_DAY))
				.isInstanceOf(ContractStateException.class);
		assertThatThrownBy(() -> penalty.accrueDuePenalty(UUID.randomUUID(), FIRST_CHARGED_DAY))
				.isInstanceOf(ContractNotFoundException.class);
		assertThat(journalEntryCount("PENALTY_ACCRUAL")).isZero();
	}

	private void billInterest(LocalDate businessDate) {
		billing.billDueInterest(contractId, businessDate);
	}

	private UUID activateContract(String interestRate) {
		ContractResponse draft = commands.create("it-penalty-key-" + UUID.randomUUID(), new CreateContractRequest(
				new CreateCustomerRequest("Budi Santoso", "3171012501900001", "08123456789", "Jakarta"),
				new CreateAssetRequest(AssetType.MOTORCYCLE, "Honda", "Beat", null, "B1234XY"),
				new BigDecimal("20000000.00"), new BigDecimal("4000000.00"), 12, InterestScheme.FLAT,
				new BigDecimal(interestRate), LocalDate.of(2026, 1, 31)));
		commands.activate(draft.id(), null);
		return draft.id();
	}

	private UUID installmentId(int periodNo) {
		return jdbc.queryForObject("select id from installment where contract_id = ? and period_no = ?",
				UUID.class, contractId, periodNo);
	}

	private int accrualCount(int periodNo) {
		return jdbc.queryForObject("select count(*) from penalty_accrual where installment_id = ?",
				Integer.class, installmentId(periodNo));
	}

	private List<LocalDate> accrualDatesOf(int periodNo) {
		return jdbc.queryForList("select accrual_date from penalty_accrual where installment_id = ? "
				+ "order by accrual_date", LocalDate.class, installmentId(periodNo));
	}

	private List<Integer> daysLateOf(int periodNo) {
		return jdbc.queryForList("select days_late from penalty_accrual where installment_id = ? "
				+ "order by accrual_date", Integer.class, installmentId(periodNo));
	}

	private BigDecimal accrualTotalOf(int periodNo) {
		return jdbc.queryForObject("select coalesce(sum(amount), 0) from penalty_accrual where installment_id = ?",
				BigDecimal.class, installmentId(periodNo));
	}

	private BigDecimal accrualAmountOf(int periodNo, LocalDate accrualDate) {
		return jdbc.queryForObject("select amount from penalty_accrual where installment_id = ? and accrual_date = ?",
				BigDecimal.class, installmentId(periodNo), accrualDate);
	}

	private UUID accrualIdOf(int periodNo, LocalDate accrualDate) {
		return jdbc.queryForObject("select id from penalty_accrual where installment_id = ? and accrual_date = ?",
				UUID.class, installmentId(periodNo), accrualDate);
	}

	private BigDecimal penaltyAmountOf(int periodNo) {
		return jdbc.queryForObject("select penalty_amount from installment where id = ?", BigDecimal.class,
				installmentId(periodNo));
	}

	/** "pokok+bunga yang belum dibayar" of TS §4.3, read straight from the aggregate this story feeds. */
	private BigDecimal unpaidPrincipalAndInterestOf(int periodNo) {
		return jdbc.queryForObject("select principal_amount + recognized_interest_amount - paid_amount "
				+ "- settled_amount - written_off_amount from installment where id = ?", BigDecimal.class,
				installmentId(periodNo));
	}

	private String statusOf(int periodNo) {
		return jdbc.queryForObject("select status from installment where id = ?", String.class,
				installmentId(periodNo));
	}

	/** The accrual entry of one charged day, keyed by the accrual row it booked (ADR-012 decision 6). */
	private UUID accrualEntryOf(int periodNo, LocalDate accrualDate) {
		return jdbc.queryForObject("select id from journal_entry where ref_type = 'PENALTY_ACCRUAL' and ref_id = ?",
				UUID.class, accrualIdOf(periodNo, accrualDate));
	}

	private OffsetDateTime entryDateOf(UUID entryId) {
		return jdbc.queryForObject("select entry_date from journal_entry where id = ?", OffsetDateTime.class, entryId);
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

	private long journalEntryCount(String refType) {
		return jdbc.queryForObject("select count(*) from journal_entry where ref_type = ?", Long.class, refType);
	}

	/** Test-only SQL: the states D1 must skip are unreachable through the story's own write paths. */
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
