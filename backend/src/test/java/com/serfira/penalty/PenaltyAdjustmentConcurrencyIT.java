package com.serfira.penalty;

import com.serfira.TestcontainersConfiguration;
import com.serfira.contract.api.ContractResponse;
import com.serfira.contract.api.CreateAssetRequest;
import com.serfira.contract.api.CreateContractRequest;
import com.serfira.contract.api.CreateCustomerRequest;
import com.serfira.contract.application.ContractCommandService;
import com.serfira.contract.application.InstallmentBillingPort;
import com.serfira.contract.application.InstallmentStatusRecomputePort;
import com.serfira.contract.domain.AssetType;
import com.serfira.contract.domain.InterestScheme;
import com.serfira.penalty.application.PenaltyAdjustmentResult;
import com.serfira.penalty.application.PenaltyAdjustmentRetriesExhaustedException;
import com.serfira.penalty.application.PenaltyAdjustmentRetryingService;
import com.serfira.penalty.domain.PenaltyAdjustmentType;
import com.serfira.penalty.application.PenaltyAccrualPort;
import com.serfira.shared.audit.AuditContext;
import com.serfira.shared.clock.Clock;
import com.serfira.shared.clock.FixedClock;
import com.serfira.shared.concurrency.Sleeper;
import com.serfira.shared.error.ConflictException;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * F4 (PR #7 review, task T15): two concurrent waivers on the same installment whose amounts individually
 * pass the remaining-penalty cap but together exceed it must not both commit (invariant 9). The waiver path
 * forces an optimistic version increment on the installment, so the two writers collide on {@code @Version};
 * {@link PenaltyAdjustmentRetryingService} retries the loser, which the (now-correct) cap pre-check then
 * rejects — exactly one waiver survives and {@code Σ adjustment ≤ remaining} holds.
 *
 * <p>The collision is forced deterministically rather than left to real thread scheduling (mirroring
 * {@code PaymentConflictRetryIT}): a blocking decorator over {@link InstallmentStatusRecomputePort} parks
 * the first waiver after both writers have read the pre-waiver version and lets the second commit first.
 */
@Import({TestcontainersConfiguration.class, PenaltyAdjustmentConcurrencyIT.ConcurrencyConfig.class})
@SpringBootTest
class PenaltyAdjustmentConcurrencyIT {

	private static final UUID SYSTEM_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

	/** Period 1 is due 2026-02-28; 2026-03-05 charges two late days outside the 3-day grace. */
	private static final LocalDate ACCRUAL_DATE = LocalDate.of(2026, 3, 5);

	/** Two charged days at 1,573.33/day = gross recognized penalty on period 1. */
	private static final BigDecimal GROSS_PENALTY = new BigDecimal("3146.66");

	/** Each waiver (2,000.00) passes the cap alone (<= 3,146.66) but together they exceed it (4,000.00). */
	private static final BigDecimal EACH_WAIVER = new BigDecimal("2000.00");

	@Autowired
	PenaltyAdjustmentRetryingService adjustments;

	@Autowired
	ContractCommandService contracts;

	@Autowired
	InstallmentBillingPort billing;

	@Autowired
	PenaltyAccrualPort penalty;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	BlockingStatusRecomputePort blockingRecompute;

	@Autowired
	AuditContext auditContext;

	private UUID actorId;
	private UUID contractId;
	private UUID installmentId;

	@BeforeEach
	void seedActivatedContractWithAccruedPenalty() {
		truncateDomainTables();
		jdbc.update("delete from app_user where id <> ?", SYSTEM_USER_ID);
		actorId = jdbc.queryForObject("""
				insert into app_user (username, password_hash, full_name, role, is_active, created_at, updated_at)
					values (?, 'test-hash', 'Concurrency IT Actor', 'ADMIN_OPERASIONAL', TRUE,
						clock_timestamp(), clock_timestamp())
					returning id
				""", UUID.class, "concurrency-it-actor-" + UUID.randomUUID());

		ContractResponse draft = contracts.create("waiver-concurrency-contract-" + UUID.randomUUID(),
				new CreateContractRequest(
						new CreateCustomerRequest("Budi Santoso", "3171012501900001", "08123456789", "Jakarta"),
						new CreateAssetRequest(AssetType.MOTORCYCLE, "Honda", "Beat", null, "B1234XY"),
						new BigDecimal("20000000.00"), new BigDecimal("4000000.00"), 12, InterestScheme.FLAT,
						new BigDecimal("0.0150"), LocalDate.of(2026, 1, 31)));
		contracts.activate(draft.id(), null);
		contractId = draft.id();

		billing.billDueInterest(contractId, ACCRUAL_DATE);
		penalty.accrueDuePenalty(contractId, ACCRUAL_DATE);
		installmentId = jdbc.queryForObject(
				"select id from installment where contract_id = ? and period_no = 1", UUID.class, contractId);
		assertThat(jdbc.queryForObject("select penalty_amount from installment where id = ?",
				BigDecimal.class, installmentId)).isEqualByComparingTo(GROSS_PENALTY);

		blockingRecompute.reset();
	}

	@AfterEach
	void cleanUp() {
		blockingRecompute.release();
		truncateDomainTables();
	}

	@Test
	void twoConcurrentOverRemainingWaiversLeaveExactlyOneCommittedWithinTheCap() throws Exception {
		// Both waivers pass the pre-check against the full remaining penalty (3,146.66). The first is parked
		// inside its transaction after it has read the installment version; the second commits first and
		// bumps @Version, so the first's forced increment loses the race, is retried, and the cap now
		// rejects it (the committed 2,000.00 leaves only 1,146.66 < 2,000.00).
		blockingRecompute.armBlockOnFirstCall();
		ExecutorService pool = Executors.newFixedThreadPool(1);
		try {
			Future<PenaltyAdjustmentResult> firstWaiver = pool.submit(() -> {
				auditContext.setActor(actorId);
				try {
					return adjustments.adjust(contractId, installmentId, PenaltyAdjustmentType.WAIVE, EACH_WAIVER,
							"first concurrent waiver");
				} finally {
					auditContext.resetToSystem();
				}
			});

			// Wait until the first waiver is parked (it has read the version), then run the second to commit.
			assertThat(blockingRecompute.awaitBlocked()).isTrue();
			auditContext.setActor(actorId);
			Throwable secondOutcome;
			try {
				secondOutcome = catchThrowable(() -> adjustments.adjust(contractId, installmentId,
						PenaltyAdjustmentType.WAIVE, EACH_WAIVER, "second concurrent waiver"));
			} finally {
				auditContext.resetToSystem();
			}
			assertThat(secondOutcome).as("second waiver commits while the first is parked").isNull();

			// Release the first; its forced version increment collides, it is retried, and the cap rejects it.
			blockingRecompute.releaseBlocked();
			Throwable firstOutcome = catchThrowable(() -> firstWaiver.get(30, TimeUnit.SECONDS));
			assertThat(firstOutcome).isNotNull();
			assertThat(rootCause(firstOutcome))
					.isInstanceOfAny(ConflictException.class, PenaltyAdjustmentRetriesExhaustedException.class);

			// Exactly one adjustment committed and the invariant-9 cap holds: Σ adjustment <= gross penalty.
			assertThat(jdbc.queryForObject(
					"select count(*) from penalty_adjustment where installment_id = ?", Long.class, installmentId))
					.isEqualTo(1L);
			BigDecimal committed = jdbc.queryForObject(
					"select coalesce(sum(amount), 0) from penalty_adjustment where installment_id = ?",
					BigDecimal.class, installmentId);
			assertThat(committed).isEqualByComparingTo(EACH_WAIVER);
			assertThat(committed).isLessThanOrEqualTo(GROSS_PENALTY);
			// One PENALTY_WAIVER journal entry only — the loser wrote nothing.
			assertThat(jdbc.queryForObject("select count(*) from journal_entry where ref_type = 'PENALTY_WAIVER'",
					Long.class)).isEqualTo(1L);
		} finally {
			pool.shutdownNow();
			assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
		}
	}

	private static Throwable rootCause(Throwable thrown) {
		Throwable cause = thrown;
		while (cause.getCause() != null && cause.getCause() != cause) {
			cause = cause.getCause();
		}
		return cause;
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

	/**
	 * Decorates the real {@link InstallmentStatusRecomputePort} so the first waiver can be parked inside its
	 * transaction, after it has read the installment version but before it commits — the window a concurrent
	 * waiver occupies. Only the first call blocks; a retried attempt proceeds straight through.
	 */
	static final class BlockingStatusRecomputePort implements InstallmentStatusRecomputePort {

		private final InstallmentStatusRecomputePort delegate;
		private final AtomicBoolean armed = new AtomicBoolean(false);
		private volatile CountDownLatch entered = new CountDownLatch(1);
		private volatile CountDownLatch release = new CountDownLatch(1);

		BlockingStatusRecomputePort(InstallmentStatusRecomputePort delegate) {
			this.delegate = delegate;
		}

		@Override
		public void recomputeAfterPenaltyAdjustment(UUID contractId, UUID installmentId) {
			// Run the real recompute first (it reads the version and schedules the forced increment), then
			// park before the surrounding transaction commits, so the other writer commits in the meantime.
			delegate.recomputeAfterPenaltyAdjustment(contractId, installmentId);
			if (armed.compareAndSet(true, false)) {
				entered.countDown();
				await(release);
			}
		}

		void armBlockOnFirstCall() {
			armed.set(true);
		}

		boolean awaitBlocked() throws InterruptedException {
			return entered.await(10, TimeUnit.SECONDS);
		}

		void releaseBlocked() {
			release.countDown();
		}

		void reset() {
			armed.set(false);
			entered = new CountDownLatch(1);
			release = new CountDownLatch(1);
		}

		void release() {
			release.countDown();
		}

		private static void await(CountDownLatch latch) {
			try {
				if (!latch.await(20, TimeUnit.SECONDS)) {
					throw new IllegalStateException("recompute test double was not released");
				}
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("recompute test double interrupted", interrupted);
			}
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class ConcurrencyConfig {

		@Bean
		@Primary
		Clock fixedClock() {
			return new FixedClock(ACCRUAL_DATE);
		}

		@Bean
		@Primary
		Sleeper noOpSleeper() {
			return millis -> {
				// No-op in tests: the backoff duration is covered by the focused unit test; this suite only
				// needs the attempt count and the collision to be real.
			};
		}

		@Bean
		BlockingStatusRecomputePort blockingStatusRecomputePort(
				com.serfira.contract.application.InstallmentStatusRecomputeService real) {
			return new BlockingStatusRecomputePort(real);
		}

		@Bean
		@Primary
		InstallmentStatusRecomputePort primaryStatusRecomputePort(BlockingStatusRecomputePort blocking) {
			return blocking;
		}
	}
}
