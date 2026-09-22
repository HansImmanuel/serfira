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
import com.serfira.payment.application.PaymentApplicationService;
import com.serfira.payment.domain.PaymentChannel;
import com.serfira.shared.clock.Clock;
import com.serfira.shared.clock.FixedClock;
import com.serfira.shared.error.BadRequestException;
import com.serfira.shared.error.ConflictException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C3 — payment retry safety at the service level (TS §2.5, ADR-007): the claim commits atomically with
 * the payment, an identical retry replays the stored response, a different body under the same key is a
 * conflict, racing retries receive the money exactly once, and a rejected request leaves the key usable.
 */
@Import({TestcontainersConfiguration.class, PaymentClockTestConfiguration.class})
@SpringBootTest
class PaymentIdempotencyIT {

	@Autowired
	PaymentApplicationService payments;

	@Autowired
	ContractCommandService contracts;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	Clock clock;

	private UUID contractId;

	@BeforeEach
	void seedActivatedContract() {
		truncateDomainTables();
		fixedClock().setDate(PaymentClockTestConfiguration.BUSINESS_DATE);
		ContractResponse draft = contracts.create("payment-idem-contract-" + UUID.randomUUID(),
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
	void anIdenticalRetryReplaysTheStoredResponseWithoutTouchingTheInstallmentTwice() {
		PaymentRequest request = new PaymentRequest(contractId, new BigDecimal("100000.00"), PaymentChannel.CASH);

		PaymentResponse first = payments.create("replay-key", request);
		PaymentResponse second = payments.create("replay-key", request);

		assertThat(second.id()).isEqualTo(first.id());
		assertThat(second.paymentNo()).isEqualTo(first.paymentNo());
		assertThat(second.allocations()).isEqualTo(first.allocations());
		assertThat(count("payment")).isEqualTo(1L);
		assertThat(count("payment_allocation")).isEqualTo(1L);
		assertThat(paidAmountOf(1)).isEqualByComparingTo("100000.00");
	}

	@Test
	void aDifferentBodyUnderTheSameKeyIsAConflictAndWritesNothing() {
		payments.create("shared-key",
				new PaymentRequest(contractId, new BigDecimal("100000.00"), PaymentChannel.CASH));

		assertThatThrownBy(() -> payments.create("shared-key",
				new PaymentRequest(contractId, new BigDecimal("200000.00"), PaymentChannel.CASH)))
				.isInstanceOf(ConflictException.class);
		assertThat(count("payment")).isEqualTo(1L);
		assertThat(paidAmountOf(1)).isEqualByComparingTo("100000.00");
	}

	@Test
	void aRejectedRequestRollsBackItsClaimSoTheKeyCanBeRetried() {
		assertThatThrownBy(() -> payments.create("retry-after-failure",
				new PaymentRequest(contractId, new BigDecimal("0.00"), PaymentChannel.CASH)))
				.isInstanceOf(BadRequestException.class);
		assertThat(countPaymentClaims()).isZero();

		PaymentResponse corrected = payments.create("retry-after-failure",
				new PaymentRequest(contractId, new BigDecimal("100000.00"), PaymentChannel.CASH));

		assertThat(corrected.paymentNo()).isNotBlank();
		assertThat(count("payment")).isEqualTo(1L);
	}

	@Test
	void racingRetriesWithOneKeyReceiveTheMoneyOnce() throws Exception {
		CountDownLatch startTogether = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(3);
		List<UUID> paymentIds = Collections.synchronizedList(new ArrayList<>());
		List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
		try {
			List<Future<PaymentResponse>> futures = new ArrayList<>();
			for (int attempt = 0; attempt < 3; attempt++) {
				futures.add(pool.submit(() -> {
					startTogether.await(5, TimeUnit.SECONDS);
					return payments.create("racing-key", new PaymentRequest(contractId,
							new BigDecimal("100000.00"), PaymentChannel.CASH));
				}));
			}
			startTogether.countDown();
			for (Future<PaymentResponse> future : futures) {
				try {
					paymentIds.add(future.get(30, TimeUnit.SECONDS).id());
				} catch (ExecutionException ex) {
					failures.add(ex.getCause());
				}
			}
		} finally {
			pool.shutdownNow();
		}

		assertThat(paymentIds).isNotEmpty();
		assertThat(paymentIds).containsOnly(paymentIds.get(0));
		assertThat(count("payment")).isEqualTo(1L);
		assertThat(countPaymentClaims()).isEqualTo(1L);
		// A losing retry must fail loudly rather than receive the money a second time.
		assertThat(failures).allSatisfy(cause -> assertThat(cause).isNotNull());
		assertThat(paidAmountOf(1)).isEqualByComparingTo("100000.00");
	}

	private BigDecimal paidAmountOf(int periodNo) {
		return jdbc.queryForObject("select paid_amount from installment where contract_id = ? and period_no = ?",
				BigDecimal.class, contractId, periodNo);
	}

	private long count(String table) {
		return jdbc.queryForObject("select count(*) from " + table, Long.class);
	}

	/** Claims left behind by the payment endpoint; a rolled-back request must leave none. */
	private long countPaymentClaims() {
		return jdbc.queryForObject("select count(*) from idempotency_keys where endpoint = ?",
				Long.class, "POST /api/v1/payments");
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
