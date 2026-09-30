package com.serfira.contract;

import com.serfira.TestcontainersConfiguration;
import com.serfira.contract.api.ContractResponse;
import com.serfira.contract.api.CreateAssetRequest;
import com.serfira.contract.api.CreateContractRequest;
import com.serfira.contract.api.CreateCustomerRequest;
import com.serfira.contract.application.ContractCommandService;
import com.serfira.contract.application.ContractNotFoundException;
import com.serfira.contract.application.InstallmentAgingPort;
import com.serfira.contract.domain.AssetType;
import com.serfira.contract.domain.ContractStateException;
import com.serfira.contract.domain.InterestScheme;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T6 — {@link InstallmentAgingPort} against a real PostgreSQL (DM §1.4, ADR-013 A-4/A-5): the boundary day,
 * idempotence without a version bump, and the states aging must never touch.
 *
 * <p>Every call runs in its own committed {@link TransactionTemplate} transaction, as the daily job's aging
 * transaction does, so the V3 deferred amount invariants and V4 coherence CHECKs apply exactly as in
 * production.
 *
 * <p>Fixture: 12 × FLAT 1.5% from 2026-01-31 with the seeded 3-day grace. Period 1 is due 2026-02-28, so
 * 2026-03-03 is its last free day and 2026-03-04 its first overdue day; period 2 is due 2026-03-31.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class InstallmentAgingServiceIT {

	private static final LocalDate LAST_FREE_DAY = LocalDate.of(2026, 3, 3);
	private static final LocalDate FIRST_OVERDUE_DAY = LocalDate.of(2026, 3, 4);
	private static final String PERIOD_PRINCIPAL = "1333333.33";

	@Autowired
	InstallmentAgingPort aging;

	@Autowired
	ContractCommandService commands;

	@Autowired
	TransactionTemplate transactions;

	@Autowired
	JdbcTemplate jdbc;

	private UUID contractId;

	@BeforeEach
	void createActivatedContract() {
		truncateDomainTables();
		contractId = activateContract();
	}

	@AfterEach
	void leaveCleanSharedDatabase() {
		truncateDomainTables();
	}

	@Test
	void theFirstOverdueDayMarksOnlyTheInstallmentWhoseGraceHasEnded() {
		assertThat(jdbc.queryForObject("select grace_period_days from contract where id = ?", Integer.class,
				contractId)).isEqualTo(3);

		assertThat(age(LAST_FREE_DAY)).isZero();
		assertThat(statusOf(1)).isEqualTo("PENDING");

		assertThat(age(FIRST_OVERDUE_DAY)).isEqualTo(1);
		assertThat(statusOf(1)).isEqualTo("OVERDUE");
		assertThat(statusOf(2)).isEqualTo("PENDING");
		assertThat(jdbc.queryForObject("select count(*) from installment where contract_id = ? "
				+ "and period_no > 1 and status <> 'PENDING'", Long.class, contractId)).isZero();
	}

	@Test
	void aSecondCallForTheSameDateWritesNothingAndKeepsTheVersion() {
		age(FIRST_OVERDUE_DAY);
		long versionAfterFirstRun = versionOf(1);
		long untouchedVersion = versionOf(2);

		assertThat(age(FIRST_OVERDUE_DAY)).isZero();

		assertThat(statusOf(1)).isEqualTo("OVERDUE");
		assertThat(versionOf(1)).isEqualTo(versionAfterFirstRun);
		assertThat(versionOf(2)).isEqualTo(untouchedVersion);
	}

	@Test
	void resolvedInstallmentsAreNeverAgedAndAPartialPaymentKeepsItsHistory() {
		seedPeriod(1, "paid_amount = " + PERIOD_PRINCIPAL + ", status = 'PAID', paid_at = clock_timestamp()");
		seedPeriod(2, "settled_amount = " + PERIOD_PRINCIPAL + ", status = 'SETTLED', "
				+ "settled_at = clock_timestamp()");
		seedPeriod(3, "written_off_amount = " + PERIOD_PRINCIPAL + ", status = 'WRITTEN_OFF', "
				+ "written_off_at = clock_timestamp()");
		seedPeriod(4, "paid_amount = 1000000.00, status = 'PARTIALLY_PAID', paid_at = clock_timestamp()");
		long paidVersion = versionOf(1);
		long settledVersion = versionOf(2);
		long writtenOffVersion = versionOf(3);

		// Period 4 is due 2026-05-31, so its first overdue day is 2026-06-04; period 5 (2026-06-30) is not due.
		assertThat(age(LocalDate.of(2026, 6, 4))).isEqualTo(1);

		assertThat(statusOf(1)).isEqualTo("PAID");
		assertThat(statusOf(2)).isEqualTo("SETTLED");
		assertThat(statusOf(3)).isEqualTo("WRITTEN_OFF");
		assertThat(versionOf(1)).isEqualTo(paidVersion);
		assertThat(versionOf(2)).isEqualTo(settledVersion);
		assertThat(versionOf(3)).isEqualTo(writtenOffVersion);
		assertThat(statusOf(4)).isEqualTo("OVERDUE");
		// V4: OVERDUE may carry paid_at; the payment history stays intact.
		assertThat(jdbc.queryForObject("select paid_at is not null from installment where contract_id = ? "
				+ "and period_no = 4", Boolean.class, contractId)).isTrue();
		assertThat(statusOf(5)).isEqualTo("PENDING");
	}

	@Test
	void refusesUnknownAndNonActiveContractsAndACallWithoutTransaction() {
		ContractResponse draft = commands.create("it-aging-draft-" + UUID.randomUUID(), new CreateContractRequest(
				new CreateCustomerRequest("Siti Aminah", "3171012501900002", "08123456780", "Bandung"),
				new CreateAssetRequest(AssetType.MOTORCYCLE, "Yamaha", "Mio", null, "B9999ZZ"),
				new BigDecimal("20000000.00"), new BigDecimal("4000000.00"), 12, InterestScheme.FLAT,
				new BigDecimal("0.0150"), LocalDate.of(2026, 1, 31)));

		assertThatThrownBy(() -> age(draft.id(), FIRST_OVERDUE_DAY)).isInstanceOf(ContractStateException.class);
		assertThatThrownBy(() -> age(UUID.randomUUID(), FIRST_OVERDUE_DAY))
				.isInstanceOf(ContractNotFoundException.class);
		// Outside a transaction the transition would be lost silently; the port refuses instead.
		assertThatThrownBy(() -> aging.markOverdueInstallments(contractId, FIRST_OVERDUE_DAY))
				.isInstanceOf(IllegalTransactionStateException.class);
		assertThat(statusOf(1)).isEqualTo("PENDING");
	}

	private int age(LocalDate businessDate) {
		return age(contractId, businessDate);
	}

	private int age(UUID contract, LocalDate businessDate) {
		Integer marked = transactions.execute(status -> aging.markOverdueInstallments(contract, businessDate));
		return marked == null ? 0 : marked;
	}

	private UUID activateContract() {
		ContractResponse draft = commands.create("it-aging-key-" + UUID.randomUUID(), new CreateContractRequest(
				new CreateCustomerRequest("Budi Santoso", "3171012501900001", "08123456789", "Jakarta"),
				new CreateAssetRequest(AssetType.MOTORCYCLE, "Honda", "Beat", null, "B1234XY"),
				new BigDecimal("20000000.00"), new BigDecimal("4000000.00"), 12, InterestScheme.FLAT,
				new BigDecimal("0.0150"), LocalDate.of(2026, 1, 31)));
		commands.activate(draft.id(), null);
		return draft.id();
	}

	private String statusOf(int periodNo) {
		return jdbc.queryForObject("select status from installment where contract_id = ? and period_no = ?",
				String.class, contractId, periodNo);
	}

	private long versionOf(int periodNo) {
		return jdbc.queryForObject("select version from installment where contract_id = ? and period_no = ?",
				Long.class, contractId, periodNo);
	}

	/** Test-only SQL: PAID/SETTLED/WRITTEN_OFF fixtures without running the payment/settlement flows. */
	private void seedPeriod(int periodNo, String assignments) {
		jdbc.update("update installment set " + assignments + " where contract_id = ? and period_no = ?",
				contractId, periodNo);
	}

	private void truncateDomainTables() {
		// Never delete/truncate ShedLock rows at runtime: JdbcTemplateLockProvider caches known lock names.
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
