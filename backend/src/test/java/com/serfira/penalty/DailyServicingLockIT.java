package com.serfira.penalty;

import com.serfira.TestcontainersConfiguration;
import com.serfira.contract.api.ContractResponse;
import com.serfira.contract.api.CreateAssetRequest;
import com.serfira.contract.api.CreateContractRequest;
import com.serfira.contract.api.CreateCustomerRequest;
import com.serfira.contract.application.ContractCommandService;
import com.serfira.contract.application.InstallmentAgingPort;
import com.serfira.contract.application.InstallmentBillingPort;
import com.serfira.contract.domain.AssetType;
import com.serfira.contract.domain.InterestScheme;
import com.serfira.penalty.application.DailyServicingContractProcessor;
import com.serfira.penalty.application.LockedDailyServicingJob;
import com.serfira.penalty.application.PenaltyAccrualPort;
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
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Proves concurrent direct/backfill invocations use the same ShedLock as the scheduled entry point. */
@Import({TestcontainersConfiguration.class, DailyServicingLockIT.LockTestConfig.class})
@SpringBootTest
class DailyServicingLockIT {

	private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 3, 5);

	@Autowired
	LockedDailyServicingJob job;

	@Autowired
	ContractCommandService commands;

	@Autowired
	BlockingContractProcessor processor;

	@Autowired
	JdbcTemplate jdbc;

	@BeforeEach
	void prepareContract() {
		truncateDomainTables();
		ContractResponse draft = commands.create("daily-lock-" + UUID.randomUUID(), new CreateContractRequest(
				new CreateCustomerRequest("Lock Customer", "3171012501900099", "081230009999", "Jakarta"),
				new CreateAssetRequest(AssetType.MOTORCYCLE, "Honda", "Beat", null, "B9999JOB"),
				new BigDecimal("20000000.00"), new BigDecimal("4000000.00"), 12, InterestScheme.FLAT,
				new BigDecimal("0.0150"), LocalDate.of(2026, 1, 31)));
		commands.activate(draft.id(), null);
		processor.reset();
	}

	@AfterEach
	void cleanDatabase() {
		processor.release();
		truncateDomainTables();
	}

	@Test
	void concurrentInvocationsAllowOnlyOneRunToProcessContracts() throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<?> first = pool.submit(() -> job.run(BUSINESS_DATE));
			assertThat(processor.awaitEntry()).isTrue();

			Future<?> second = pool.submit(() -> job.run(BUSINESS_DATE));
			second.get(10, TimeUnit.SECONDS);
			assertThat(processor.invocations()).isEqualTo(1);

			processor.release();
			first.get(30, TimeUnit.SECONDS);
		} finally {
			pool.shutdownNow();
			assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
		}

		assertThat(processor.invocations()).isEqualTo(1);
		// One invocation writes one row per step: billing, penalty-accrual and aging (A-9, T6).
		assertThat(jdbc.queryForObject("select count(*) from job_run where business_date = ?", Long.class,
				BUSINESS_DATE)).isEqualTo(3L);
		assertThat(jdbc.queryForObject("select count(*) from job_run where status = 'COMPLETED' "
				+ "and records_processed = 1 and records_failed = 0", Long.class)).isEqualTo(3L);
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

	static class BlockingContractProcessor extends DailyServicingContractProcessor {

		private final AtomicInteger invocationCount = new AtomicInteger();
		private volatile CountDownLatch entered = new CountDownLatch(1);
		private volatile CountDownLatch release = new CountDownLatch(1);

		BlockingContractProcessor(InstallmentBillingPort billing, PenaltyAccrualPort penalty,
				InstallmentAgingPort aging) {
			super(billing, penalty, aging);
		}

		@Override
		@Transactional
		public void process(UUID contractId, LocalDate businessDate) {
			invocationCount.incrementAndGet();
			entered.countDown();
			try {
				if (!release.await(20, TimeUnit.SECONDS)) {
					throw new IllegalStateException("test processor was not released");
				}
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("test processor interrupted", exception);
			}
			super.process(contractId, businessDate);
		}

		void reset() {
			invocationCount.set(0);
			entered = new CountDownLatch(1);
			release = new CountDownLatch(1);
		}

		boolean awaitEntry() throws InterruptedException {
			return entered.await(10, TimeUnit.SECONDS);
		}

		void release() {
			release.countDown();
		}

		int invocations() {
			return invocationCount.get();
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class LockTestConfig {
		@Bean
		@Primary
		Clock fixedClock() {
			return new FixedClock(BUSINESS_DATE);
		}

		@Bean
		@Primary
		BlockingContractProcessor blockingContractProcessor(InstallmentBillingPort billing,
				PenaltyAccrualPort penalty, InstallmentAgingPort aging) {
			return new BlockingContractProcessor(billing, penalty, aging);
		}
	}
}
