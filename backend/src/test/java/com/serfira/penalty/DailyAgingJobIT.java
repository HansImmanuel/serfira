package com.serfira.penalty;

import com.serfira.TestcontainersConfiguration;
import com.serfira.contract.api.ContractResponse;
import com.serfira.contract.api.CreateAssetRequest;
import com.serfira.contract.api.CreateContractRequest;
import com.serfira.contract.api.CreateCustomerRequest;
import com.serfira.contract.application.ContractCommandService;
import com.serfira.contract.domain.AssetType;
import com.serfira.contract.domain.InterestScheme;
import com.serfira.payment.api.PaymentRequest;
import com.serfira.payment.application.PaymentRetryingService;
import com.serfira.payment.domain.PaymentChannel;
import com.serfira.penalty.application.LockedDailyServicingJob;
import com.serfira.shared.clock.Clock;
import com.serfira.shared.clock.FixedClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T6 — the aging step of the daily job through {@link LockedDailyServicingJob}, against PostgreSQL and the real
 * billing, penalty, payment and aging ports (DM §1.4, ADR-013 A-4/A-5 and implementation note T6).
 *
 * <p>Fixture: 12 × FLAT 1.5% from 2026-01-31 with the seeded 3-day grace. Period 1 is due 2026-02-28 with
 * 1,333,333.33 principal + 240,000.00 interest, so 2026-03-03 is its last free day and 2026-03-04 its first
 * overdue (and first chargeable) day, on which one day of penalty is 1,573,333.33 × 0.0010 = 1,573.33.
 */
@Import({TestcontainersConfiguration.class, DailyAgingJobIT.FixedClockConfig.class})
@SpringBootTest
class DailyAgingJobIT {

	private static final LocalDate LAST_FREE_DAY = LocalDate.of(2026, 3, 3);
	private static final LocalDate FIRST_OVERDUE_DAY = LocalDate.of(2026, 3, 4);
	/** Principal 1,333,333.33 + interest 240,000.00 + one day of penalty 1,573.33. */
	private static final BigDecimal PERIOD_ONE_PAYOFF_ON_FIRST_OVERDUE_DAY = new BigDecimal("1574906.66");
	private static final String PERIOD_PRINCIPAL = "1333333.33";

	@Autowired
	LockedDailyServicingJob job;

	@Autowired
	ContractCommandService commands;

	@Autowired
	PaymentRetryingService payments;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	Clock clock;

	@BeforeEach
	void resetState() {
		truncateDomainTables();
		fixedClock().setDate(LAST_FREE_DAY);
	}

	@AfterEach
	void leaveSharedDatabaseClean() {
		truncateDomainTables();
	}

	@Test
	void theJobMarksAnInstallmentOverdueOnItsFirstOverdueDayAndNotBefore() {
		UUID contractId = createContract(1, true);

		runOn(LAST_FREE_DAY);

		assertThat(statusOf(contractId, 1)).isEqualTo("PENDING");
		assertAgingRun(LAST_FREE_DAY, 1, 0, "COMPLETED");

		runOn(FIRST_OVERDUE_DAY);

		assertThat(statusOf(contractId, 1)).isEqualTo("OVERDUE");
		assertThat(countNotPendingAfterPeriodOne(contractId)).isZero();
		assertAgingRun(FIRST_OVERDUE_DAY, 1, 0, "COMPLETED");
	}

	@Test
	void aRerunForTheSameDateChangesNoStatusAndNoVersion() {
		UUID contractId = createContract(2, true);
		runOn(FIRST_OVERDUE_DAY);
		long overdueVersion = versionOf(contractId, 1);
		long pendingVersion = versionOf(contractId, 2);

		runOn(FIRST_OVERDUE_DAY);

		assertThat(statusOf(contractId, 1)).isEqualTo("OVERDUE");
		assertThat(versionOf(contractId, 1)).isEqualTo(overdueVersion);
		assertThat(versionOf(contractId, 2)).isEqualTo(pendingVersion);
		assertThat(jdbc.queryForObject("select count(*) from job_run where business_date = ? and job_name = 'aging'",
				Long.class, FIRST_OVERDUE_DAY)).isEqualTo(2L);
	}

	@Test
	void aPartialPaymentOnAnOverdueInstallmentMakesItPartiallyPaidUntilTheNextRun() {
		UUID contractId = createContract(3, true);
		runOn(FIRST_OVERDUE_DAY);
		assertThat(statusOf(contractId, 1)).isEqualTo("OVERDUE");

		pay(contractId, "partial-on-overdue", new BigDecimal("100000.00"));

		// A-5: a partial payment moves OVERDUE → PARTIALLY_PAID (Installment.applyPayment).
		assertThat(statusOf(contractId, 1)).isEqualTo("PARTIALLY_PAID");

		fixedClock().setDate(FIRST_OVERDUE_DAY.plusDays(1));
		runOn(FIRST_OVERDUE_DAY.plusDays(1));

		// ... and the next aging run marks it OVERDUE again because outstanding > 0 and DPD >= 1.
		assertThat(statusOf(contractId, 1)).isEqualTo("OVERDUE");
		assertThat(jdbc.queryForObject("select paid_at is not null from installment where contract_id = ? "
				+ "and period_no = 1", Boolean.class, contractId)).isTrue();
	}

	@Test
	void aFullPaymentMovesOverdueToPaidAndLaterRunsLeaveItPaid() {
		UUID contractId = createContract(4, true);
		runOn(FIRST_OVERDUE_DAY);
		assertThat(statusOf(contractId, 1)).isEqualTo("OVERDUE");

		pay(contractId, "full-on-overdue", PERIOD_ONE_PAYOFF_ON_FIRST_OVERDUE_DAY);

		assertThat(statusOf(contractId, 1)).isEqualTo("PAID");
		long paidVersion = versionOf(contractId, 1);

		fixedClock().setDate(FIRST_OVERDUE_DAY.plusDays(1));
		runOn(FIRST_OVERDUE_DAY.plusDays(1));

		assertThat(statusOf(contractId, 1)).isEqualTo("PAID");
		assertThat(versionOf(contractId, 1)).isEqualTo(paidVersion);
	}

	@Test
	void settledAndWrittenOffInstallmentsAreNeverAged() {
		UUID contractId = createContract(5, true);
		// Test-only SQL: settlement (E2) and write-off (E6) write paths do not exist yet.
		seedPeriod(contractId, 1, "settled_amount = " + PERIOD_PRINCIPAL + ", status = 'SETTLED', "
				+ "settled_at = clock_timestamp()");
		seedPeriod(contractId, 2, "written_off_amount = " + PERIOD_PRINCIPAL + ", status = 'WRITTEN_OFF', "
				+ "written_off_at = clock_timestamp()");
		long settledVersion = versionOf(contractId, 1);
		long writtenOffVersion = versionOf(contractId, 2);

		// Period 2 is due 2026-03-31, so both periods are past their first overdue day on 2026-04-04.
		LocalDate businessDate = LocalDate.of(2026, 4, 4);
		fixedClock().setDate(businessDate);
		runOn(businessDate);

		assertThat(statusOf(contractId, 1)).isEqualTo("SETTLED");
		assertThat(statusOf(contractId, 2)).isEqualTo("WRITTEN_OFF");
		assertThat(versionOf(contractId, 1)).isEqualTo(settledVersion);
		assertThat(versionOf(contractId, 2)).isEqualTo(writtenOffVersion);
		assertThat(statusOf(contractId, 3)).isEqualTo("PENDING");
		assertAgingRun(businessDate, 1, 0, "COMPLETED");
	}

	@Test
	void onlyActiveContractsAreAged() {
		UUID active = createContract(6, true);
		UUID closed = createContract(7, true);
		UUID draft = createContract(8, false);
		jdbc.update("update contract set status = 'CLOSED', closed_at = clock_timestamp(), "
				+ "closed_reason = 'MATURITY' where id = ?", closed);

		runOn(FIRST_OVERDUE_DAY);

		assertThat(statusOf(active, 1)).isEqualTo("OVERDUE");
		assertThat(statusOf(closed, 1)).isEqualTo("PENDING");
		assertThat(jdbc.queryForObject("select count(*) from installment where contract_id = ?", Long.class, draft))
				.isZero();
		assertAgingRun(FIRST_OVERDUE_DAY, 1, 0, "COMPLETED");
	}

	private void runOn(LocalDate businessDate) {
		fixedClock().setDate(businessDate);
		job.run(businessDate);
	}

	private void pay(UUID contractId, String keyPrefix, BigDecimal amount) {
		payments.create(keyPrefix + "-" + UUID.randomUUID(), new PaymentRequest(contractId, amount,
				PaymentChannel.CASH));
	}

	private UUID createContract(int sequence, boolean activate) {
		ContractResponse draft = commands.create("daily-aging-" + sequence + "-" + UUID.randomUUID(),
				new CreateContractRequest(
						new CreateCustomerRequest("Aging Customer " + sequence,
								String.format("31710125017%05d", sequence),
								String.format("08125000%04d", sequence), "Jakarta"),
						new CreateAssetRequest(AssetType.MOTORCYCLE, "Honda", "Beat", null,
								String.format("B%04dAGE", sequence)),
						new BigDecimal("20000000.00"), new BigDecimal("4000000.00"), 12,
						InterestScheme.FLAT, new BigDecimal("0.0150"), LocalDate.of(2026, 1, 31)));
		if (activate) {
			commands.activate(draft.id(), null);
		}
		return draft.id();
	}

	private String statusOf(UUID contractId, int periodNo) {
		return jdbc.queryForObject("select status from installment where contract_id = ? and period_no = ?",
				String.class, contractId, periodNo);
	}

	private long versionOf(UUID contractId, int periodNo) {
		return jdbc.queryForObject("select version from installment where contract_id = ? and period_no = ?",
				Long.class, contractId, periodNo);
	}

	private long countNotPendingAfterPeriodOne(UUID contractId) {
		return jdbc.queryForObject("select count(*) from installment where contract_id = ? and period_no > 1 "
				+ "and status <> 'PENDING'", Long.class, contractId);
	}

	private void seedPeriod(UUID contractId, int periodNo, String assignments) {
		jdbc.update("update installment set " + assignments + " where contract_id = ? and period_no = ?",
				contractId, periodNo);
	}

	private void assertAgingRun(LocalDate businessDate, int processed, int failed, String status) {
		assertThat(jdbc.queryForObject("""
				select count(*) from job_run
				where business_date = ? and job_name = 'aging' and records_processed = ?
				  and records_failed = ? and status = ? and finished_at is not null
				""", Long.class, businessDate, processed, failed, status)).isEqualTo(1L);
	}

	private FixedClock fixedClock() {
		return (FixedClock) clock;
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

	@TestConfiguration(proxyBeanMethods = false)
	static class FixedClockConfig {
		@Bean
		@Primary
		Clock fixedClock() {
			return new FixedClock(LAST_FREE_DAY);
		}
	}
}
