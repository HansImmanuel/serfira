package com.serfira.contract;

import com.serfira.TestcontainersConfiguration;
import com.serfira.contract.api.ContractResponse;
import com.serfira.contract.api.CreateAssetRequest;
import com.serfira.contract.api.CreateContractRequest;
import com.serfira.contract.api.CreateCustomerRequest;
import com.serfira.contract.application.ContractCommandService;
import com.serfira.contract.domain.AssetType;
import com.serfira.contract.domain.InterestScheme;
import com.serfira.shared.audit.AuditContext;
import com.serfira.shared.clock.Clock;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B5 — create retry safety at the service level (TS §2.5, ADR-007): a claim is committed atomically
 * with the business write, an identical retry replays the stored response instead of executing
 * again, and racing retries with one key produce exactly one contract.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ContractIdempotencyIT {

	private static final String NIK = "3171012501900001";
	private static final String PHONE = "08123456789";

	@Autowired
	ContractCommandService commands;

	@Autowired
	AuditContext auditContext;

	@Autowired
	Clock clock;

	@Autowired
	JdbcTemplate jdbc;

	@BeforeEach
	void cleanDomainTables() {
		truncateDomainTables();
	}

	@AfterEach
	void leaveCleanSharedDatabase() {
		truncateDomainTables();
	}

@Test
	void anIdenticalRetryReplaysTheStoredResponseWithoutExecutingAgain() {
		CreateContractRequest request = contractRequest("20000000.00", "4000000.00");

		ContractResponse first = commands.create("replay-key", request);
		ContractResponse second = commands.create("replay-key", request);

		assertThat(second.id()).isEqualTo(first.id());
		assertThat(second.contractNo()).isEqualTo(first.contractNo());
		assertThat(count("contract")).isEqualTo(1L);
		assertThat(count("asset")).isEqualTo(1L);
		assertThat(jdbc.queryForObject("""
				select count(*) from idempotency_keys
				where endpoint = 'POST /api/v1/contracts' and status = 'COMPLETED'
					and request_hash is not null and length(request_hash) = 64
				""", Long.class)).isEqualTo(1L);
		// Replay stores the masked projection only: no plaintext PII at rest for the retry path.
		assertThat(jdbc.queryForObject("select response_json from idempotency_keys", String.class))
				.contains(first.contractNo())
				.doesNotContain(NIK)
				.doesNotContain("628123456789");
	}

	@Test
	void theSameKeyWithADifferentPayloadIsAConflict() {
		commands.create("conflict-key", contractRequest("20000000.00", "4000000.00"));

		assertThatThrownBy(() -> commands.create("conflict-key", contractRequest("21000000.00", "4000000.00")))
				.isInstanceOf(ConflictException.class)
				.hasMessageContaining("already used");
		assertThat(count("contract")).isEqualTo(1L);
	}

	@Test
	void aMissingOrBlankKeyIsRejectedBeforeAnythingIsWritten() {
		assertThatThrownBy(() -> commands.create(null, contractRequest("20000000.00", "4000000.00")))
				.isInstanceOf(BadRequestException.class);
		assertThatThrownBy(() -> commands.create("   ", contractRequest("20000000.00", "4000000.00")))
				.isInstanceOf(BadRequestException.class);

		assertThat(count("contract")).isZero();
		assertThat(count("customer")).isZero();
		assertThat(count("idempotency_keys")).isZero();
	}

	@Test
	void racingRetriesWithOneKeyCreateExactlyOneContract() throws Exception {
		CountDownLatch startTogether = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(3);
		List<UUID> contractIds = Collections.synchronizedList(new ArrayList<>());
		List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
		try {
			List<Future<ContractResponse>> futures = new ArrayList<>();
			for (int attempt = 0; attempt < 3; attempt++) {
				futures.add(pool.submit(() -> {
					startTogether.await(5, TimeUnit.SECONDS);
					return commands.create("racing-key", contractRequest("20000000.00", "4000000.00"));
				}));
			}
			startTogether.countDown();
			for (Future<ContractResponse> future : futures) {
				try {
					contractIds.add(future.get(30, TimeUnit.SECONDS).id());
				} catch (ExecutionException ex) {
					failures.add(ex.getCause());
				}
			}
		} finally {
			pool.shutdownNow();
		}

		assertThat(contractIds).isNotEmpty();
		assertThat(contractIds).containsOnly(contractIds.get(0));
		assertThat(count("contract")).isEqualTo(1L);
		assertThat(count("idempotency_keys")).isEqualTo(1L);
		assertThat(failures).as("losing retries must fail loudly, never write a second contract: %s", failures)
				.allSatisfy(cause -> assertThat(cause).isNotNull());
	}

	@Test
	void theClaimRowCarriesTheActorAndTheConfiguredRetention() {
		UUID actor = insertActor();

		AtomicReference<ContractResponse> created = new AtomicReference<>();
		auditContext.runAs(actor, () -> created.set(commands.create("audited-key", contractRequest("20000000.00", "4000000.00"))));

		assertThat(created.get()).isNotNull();
		assertThat(jdbc.queryForObject("select created_by from idempotency_keys", UUID.class)).isEqualTo(actor);
		assertThat(jdbc.queryForObject("select created_by from contract where id = ?", UUID.class, created.get().id()))
				.isEqualTo(actor);
		assertThat(jdbc.queryForObject("select expires_at::date from idempotency_keys", java.sql.Date.class))
				.isEqualTo(java.sql.Date.valueOf(clock.today().plusDays(7)));
	}

	private static CreateContractRequest contractRequest(String assetPrice, String downPayment) {
		return new CreateContractRequest(
				new CreateCustomerRequest("Budi Santoso", NIK, PHONE, "Jakarta"),
				new CreateAssetRequest(AssetType.MOTORCYCLE, "Honda", "Beat", null, "B1234XY"),
				new BigDecimal(assetPrice), new BigDecimal(downPayment), 12, InterestScheme.FLAT,
				new BigDecimal("0.0150"), LocalDate.of(2026, 1, 31));
	}

	private UUID insertActor() {
		return jdbc.queryForObject("""
				insert into app_user (username, password_hash, full_name, role, is_active, created_at, updated_at)
					values (?, 'test-hash', 'IT Actor', 'ADMIN_OPERASIONAL', TRUE, clock_timestamp(), clock_timestamp())
					returning id
				""", UUID.class, "it-actor-" + UUID.randomUUID());
	}

	private long count(String table) {
		return jdbc.queryForObject("select count(*) from " + table, Long.class);
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