package com.serfira.payment;

import com.serfira.TestcontainersConfiguration;
import com.serfira.contract.api.ContractResponse;
import com.serfira.contract.api.CreateAssetRequest;
import com.serfira.contract.api.CreateContractRequest;
import com.serfira.contract.api.CreateCustomerRequest;
import com.serfira.contract.application.ContractCommandService;
import com.serfira.contract.domain.AssetType;
import com.serfira.contract.domain.InterestScheme;
import com.serfira.payment.api.PaymentRequest;
import com.serfira.payment.api.PaymentResponse;
import com.serfira.payment.application.PaymentConflictRetriesExhaustedException;
import com.serfira.payment.application.PaymentRetryingService;
import com.serfira.payment.domain.PaymentChannel;
import com.serfira.penalty.application.PenaltyAccrualPort;
import com.serfira.penalty.infrastructure.PenaltyAccrualRepository;
import com.serfira.shared.audit.AuditContext;
import com.serfira.shared.clock.Clock;
import com.serfira.shared.clock.FixedClock;
import com.serfira.shared.concurrency.Sleeper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T5 (Addendum §5, `docs/tasks.md`): the payment write path retries a job-versus-payment conflict on
 * {@code (installment_id, accrual_date)} instead of surfacing the race as an avoidable 409, and gives up
 * with 409 {@code CONCURRENT_MODIFICATION} only once the documented attempt budget is exhausted.
 *
 * <p>Both races are forced deterministically rather than left to real thread scheduling, exactly like
 * {@code DailyServicingLockIT}'s blocking test double.
 */
@Import({TestcontainersConfiguration.class, PaymentConflictRetryIT.RetryTestConfig.class})
@SpringBootTest
class PaymentConflictRetryIT {

	private static final LocalDate FIRST_CHARGEABLE_DATE = LocalDate.of(2026, 3, 4);

	@Autowired
	PaymentRetryingService payments;

	@Autowired
	ContractCommandService contracts;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	Clock clock;

	@Autowired
	BlockingPenaltyAccrualRepository blockingRepository;

	@Autowired
	BlockingPenaltyAccrualPort blockingAccrual;

	@Autowired
	PlatformTransactionManager transactionManager;

	private UUID contractId;
	private UUID installmentId;

	@BeforeEach
	void seedLateInstallment() {
		truncateDomainTables();
		fixedClock().setDate(LocalDate.of(2026, 2, 28));
		ContractResponse draft = contracts.create("payment-conflict-contract-" + UUID.randomUUID(),
				new CreateContractRequest(
						new CreateCustomerRequest("Budi Santoso", "3171012501900001", "08123456789", "Jakarta"),
						new CreateAssetRequest(AssetType.MOTORCYCLE, "Honda", "Beat", null, "B1234XY"),
						new BigDecimal("20000000.00"), new BigDecimal("4000000.00"), 12, InterestScheme.FLAT,
						new BigDecimal("0.0150"), LocalDate.of(2026, 1, 31)));
		contracts.activate(draft.id(), null);
		contractId = draft.id();
		installmentId = jdbc.queryForObject(
				"select id from installment where contract_id = ? and period_no = 1", UUID.class, contractId);
		fixedClock().setDate(FIRST_CHARGEABLE_DATE);
		blockingRepository.reset();
		blockingAccrual.reset();
	}

	@AfterEach
	void cleanUp() {
		blockingRepository.release();
		blockingAccrual.release();
		truncateDomainTables();
	}

	@Test
	void paymentRetriesPastAJobAccrualRaceAndReceivesTheMoneyOnce() throws Exception {
		// Blocks the payment's accrual after it has read "nothing accrued yet" (the real service's
		// alreadyAccrued lookup) but before its own INSERT — the exact window in which a concurrent job
		// commit turns the payment's insert into a uk_penalty_accrual collision, which
		// PaymentRetryingService must retry past.
		blockingRepository.armBlockBeforeFirstSave();
		ExecutorService pool = Executors.newFixedThreadPool(1);
		try {
			Future<PaymentResponse> paymentFuture = pool.submit(
					() -> payments.create("conflict-retry-key", new PaymentRequest(contractId,
							new BigDecimal("1573333.33"), PaymentChannel.CASH)));

			assertThat(blockingRepository.awaitBlockedAttempt()).isTrue();
			runJobAccrualInItsOwnTransaction();
			blockingRepository.releaseBlockedAttempt();

			PaymentResponse response = paymentFuture.get(30, TimeUnit.SECONDS);

			assertThat(response.amount()).isEqualByComparingTo("1573333.33");
			// The blocked attempt's own insert lost the race (real SQLSTATE 23505 on uk_penalty_accrual);
			// PaymentRetryingService retried the whole payment and the second attempt found the job's row
			// already accrued, so only one save was ever attempted — the collision, not a second insert.
			assertThat(blockingRepository.saveAttemptsObserved()).isEqualTo(1);
			assertThat(jdbc.queryForObject(
					"select count(*) from penalty_accrual where installment_id = ? and accrual_date = ?",
					Long.class, installmentId, FIRST_CHARGEABLE_DATE)).isEqualTo(1L);
			assertThat(jdbc.queryForObject("select count(*) from payment", Long.class)).isEqualTo(1L);
			assertThat(jdbc.queryForObject(
					"select paid_amount from installment where id = ?", BigDecimal.class, installmentId))
					.isEqualByComparingTo("1573333.33");
		} finally {
			pool.shutdownNow();
			assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
		}
	}

	@Test
	void exhaustedRetriesReturnConcurrentModificationAndLeaveTheKeyReusable() {
		blockingAccrual.armPersistentUniqueConflict();

		assertThatThrownBy(() -> payments.create("exhausted-key", new PaymentRequest(contractId,
				new BigDecimal("1573333.33"), PaymentChannel.CASH)))
				.isInstanceOf(PaymentConflictRetriesExhaustedException.class);

		assertThat(blockingAccrual.attemptsObserved()).isEqualTo(3);
		assertThat(jdbc.queryForObject("select count(*) from payment", Long.class)).isEqualTo(0L);
		assertThat(jdbc.queryForObject("select count(*) from idempotency_keys where key = ?",
				Long.class, "exhausted-key")).isEqualTo(0L);

		blockingAccrual.disarm();
		PaymentResponse recovered = payments.create("exhausted-key",
				new PaymentRequest(contractId, new BigDecimal("1573333.33"), PaymentChannel.CASH));

		assertThat(recovered.amount()).isEqualByComparingTo("1573333.33");
		assertThat(jdbc.queryForObject("select count(*) from payment", Long.class)).isEqualTo(1L);
	}

	/** Simulates the daily job's own accrual transaction, committed independently of the payment's. */
	private void runJobAccrualInItsOwnTransaction() {
		new TransactionTemplate(transactionManager).executeWithoutResult(status ->
				jdbc.update("""
						insert into penalty_accrual (id, installment_id, accrual_date, days_late, amount,
							created_at, created_by, updated_at, updated_by)
							values (?, ?, ?, 1, 1573.33, clock_timestamp(), ?, clock_timestamp(), ?)
						""", UUID.randomUUID(), installmentId, FIRST_CHARGEABLE_DATE,
						AuditContext.SYSTEM_USER_ID, AuditContext.SYSTEM_USER_ID));
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

	/**
	 * Decorates the real {@link PenaltyAccrualRepository} bean so the payment's accrual attempt can be
	 * parked right after it has read the installment's already-accrued dates (finding none yet) and right
	 * before it inserts its own row — the same window a real concurrent job run occupies.
	 *
	 * <p>A JDK dynamic proxy is used instead of implementing the interface directly: {@code JpaRepository}
	 * carries several deprecated/derived methods that {@code PenaltyAccrualService} never calls, and
	 * reimplementing all of them just to delegate would be noise with no test value.
	 */
	static final class BlockingPenaltyAccrualRepository {

		private final AtomicInteger saveAttempts = new AtomicInteger();
		private volatile CountDownLatch blockedEntered = new CountDownLatch(1);
		private volatile CountDownLatch blockedRelease = new CountDownLatch(1);
		private volatile boolean armed;

		private final PenaltyAccrualRepository proxy;

		BlockingPenaltyAccrualRepository(PenaltyAccrualRepository delegate) {
			this.proxy = (PenaltyAccrualRepository) Proxy.newProxyInstance(
					PenaltyAccrualRepository.class.getClassLoader(),
					new Class<?>[] {PenaltyAccrualRepository.class},
					(proxyInstance, method, args) -> {
						if ("save".equals(method.getName()) && args != null && args.length == 1) {
							if (armed && saveAttempts.incrementAndGet() == 1) {
								blockedEntered.countDown();
								await(blockedRelease);
							} else {
								saveAttempts.incrementAndGet();
							}
						}
						try {
							return method.invoke(delegate, args);
						} catch (InvocationTargetException invocationFailure) {
							throw invocationFailure.getCause();
						}
					});
		}

		PenaltyAccrualRepository asRepository() {
			return proxy;
		}

		/** Parks the first {@code save} call until {@link #releaseBlockedAttempt()}. */
		void armBlockBeforeFirstSave() {
			armed = true;
		}

		void reset() {
			saveAttempts.set(0);
			blockedEntered = new CountDownLatch(1);
			blockedRelease = new CountDownLatch(1);
			armed = false;
		}

		boolean awaitBlockedAttempt() throws InterruptedException {
			return blockedEntered.await(10, TimeUnit.SECONDS);
		}

		void releaseBlockedAttempt() {
			blockedRelease.countDown();
		}

		void release() {
			blockedRelease.countDown();
		}

		int saveAttemptsObserved() {
			return saveAttempts.get();
		}

		private static void await(CountDownLatch latch) {
			try {
				if (!latch.await(20, TimeUnit.SECONDS)) {
					throw new IllegalStateException("test double was not released");
				}
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("test double interrupted", interrupted);
			}
		}
	}

	/**
	 * Decorates the real {@link PenaltyAccrualPort} bean to force a persistent, deterministic
	 * {@code uk_penalty_accrual} conflict for the exhausted-retries test, without depending on real thread
	 * timing at all.
	 */
	static class BlockingPenaltyAccrualPort implements PenaltyAccrualPort {

		private final PenaltyAccrualPort delegate;
		private final AtomicInteger attempts = new AtomicInteger();
		private volatile boolean persistentConflict;

		BlockingPenaltyAccrualPort(PenaltyAccrualPort delegate) {
			this.delegate = delegate;
		}

		@Override
		public int accrueDuePenalty(UUID contractId, LocalDate businessDate) {
			attempts.incrementAndGet();
			if (persistentConflict) {
				throw uniqueConflict();
			}
			return delegate.accrueDuePenalty(contractId, businessDate);
		}

		/** Every call raises the same SQLSTATE 23505 the real unique constraint would raise. */
		void armPersistentUniqueConflict() {
			persistentConflict = true;
		}

		void disarm() {
			persistentConflict = false;
		}

		void reset() {
			attempts.set(0);
			persistentConflict = false;
		}

		void release() {
			// Nothing to release: this double never blocks.
		}

		int attemptsObserved() {
			return attempts.get();
		}

		private DataIntegrityViolationException uniqueConflict() {
			return new DataIntegrityViolationException("uk_penalty_accrual",
					new SQLException("duplicate key value violates unique constraint \"uk_penalty_accrual\"",
							"23505"));
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class RetryTestConfig {

		@Bean
		@Primary
		Clock fixedClock() {
			return new FixedClock(LocalDate.of(2026, 2, 28));
		}

		@Bean
		@Primary
		Sleeper noOpSleeper() {
			return millis -> {
				// No-op in tests: the retry policy's backoff duration is covered by the focused unit test
				// (PaymentRetryingServiceTest); this suite only needs the attempt count to be real.
			};
		}

		@Bean
		BlockingPenaltyAccrualRepository blockingPenaltyAccrualRepository(
				@Qualifier("penaltyAccrualRepository") PenaltyAccrualRepository real) {
			return new BlockingPenaltyAccrualRepository(real);
		}

		@Bean
		@Primary
		PenaltyAccrualRepository primaryPenaltyAccrualRepository(BlockingPenaltyAccrualRepository blocking) {
			return blocking.asRepository();
		}

		@Bean
		@Primary
		BlockingPenaltyAccrualPort blockingPenaltyAccrualPort(
				com.serfira.penalty.application.PenaltyAccrualService realAccrual) {
			return new BlockingPenaltyAccrualPort(realAccrual);
		}
	}
}
