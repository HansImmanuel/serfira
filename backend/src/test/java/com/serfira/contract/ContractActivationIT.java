package com.serfira.contract;

import com.serfira.TestcontainersConfiguration;
import com.serfira.contract.api.ContractResponse;
import com.serfira.contract.api.CreateAssetRequest;
import com.serfira.contract.api.CreateContractRequest;
import com.serfira.contract.api.CreateCustomerRequest;
import com.serfira.contract.application.ContractCommandService;
import com.serfira.contract.application.ContractNotFoundException;
import com.serfira.contract.domain.AssetType;
import com.serfira.contract.domain.ContractStateException;
import com.serfira.contract.domain.ContractStatus;
import com.serfira.contract.domain.Installment;
import com.serfira.contract.domain.InterestScheme;
import com.serfira.contract.infrastructure.ContractRepository;
import com.serfira.contract.infrastructure.InstallmentRepository;
import com.serfira.shared.clock.Clock;
import com.serfira.shared.config.SystemParameterKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
 * B5 — service-level integration tests for create → activate against a real PostgreSQL 16:
 * the schedule really is generated once, at activation, with the golden numbers from
 * 04_GAPS_ADDENDUM.md §18.1, and the surrounding invariants (invariant 4 one-schedule-per-contract,
 * optimistic locking, configuration snapshot timing) hold.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ContractActivationIT {

	private static final String NIK = "3171012501900001";
	private static final String PHONE = "08123456789";
	private static final LocalDate PLANNED_START = LocalDate.of(2026, 1, 31);

	@Autowired
	ContractCommandService commands;

	@Autowired
	ContractRepository contracts;

	@Autowired
	InstallmentRepository installments;

	@Autowired
	Clock clock;

	@Autowired
	JdbcTemplate jdbc;

	@BeforeEach
	void cleanDomainTables() {
		truncateDomainTables();
	}

	@AfterEach
	void removeConfigurationOverrides() {
		// Only this suite's overrides: the V1 seed rows must stay (BaselineSchemaIT counts them).
		jdbc.update("delete from system_parameter where effective_date = ? and param_key in (?, ?)",
				java.sql.Date.valueOf(clock.today()),
				SystemParameterKeys.DEFAULT_GRACE_PERIOD_DAYS,
				SystemParameterKeys.DEFAULT_PENALTY_RATE_DAILY);
		// Also leave no row referencing this suite's actor (another suite deletes app_user rows).
		truncateDomainTables();
	}

	@Test
	void activationPersistsTheGoldenScheduleExactlyOnce() {
		ContractResponse draft = createDraft("B1234XY", PLANNED_START);

		assertThat(draft.status()).isEqualTo(ContractStatus.DRAFT);
		assertThat(draft.startDate()).isNull();
		assertThat(draft.plannedStartDate()).isEqualTo(PLANNED_START);
		// PRD C-2: drafting writes no schedule at all.
		assertThat(countInstallments(draft.id())).isZero();

		ContractResponse activated = commands.activate(draft.id(), null);

		assertThat(activated.status()).isEqualTo(ContractStatus.ACTIVE);
		assertThat(activated.startDate()).isEqualTo(PLANNED_START);
		assertThat(activated.outstanding()).isEqualByComparingTo("16000000.00");
		// The effective date was pinned on the row, so V4 ck_contract_active_coherence holds.
		assertThat(activated.version()).isEqualTo(1L);

		// Golden values 04_GAPS_ADDENDUM.md §18.1: FLAT, 16,000,000, 0.0150/month, 12 periods.
		assertThat(countInstallments(draft.id())).isEqualTo(12);
		assertThat(sumOf(draft.id(), "principal_amount")).isEqualByComparingTo("16000000.00");
		assertThat(sumOf(draft.id(), "interest_amount")).isEqualByComparingTo("2880000.00");

		// Invariant 4: exactly one schedule — periods 1..12, each exactly once.
		assertThat(jdbc.queryForObject(
				"select count(distinct period_no) from installment where contract_id = ?", Integer.class, draft.id()))
				.isEqualTo(12);
		assertThat(jdbc.queryForObject(
				"select min(period_no) from installment where contract_id = ?", Integer.class, draft.id()))
				.isEqualTo(1);
		assertThat(jdbc.queryForObject(
				"select max(period_no) from installment where contract_id = ?", Integer.class, draft.id()))
				.isEqualTo(12);

		Map<String, Object> first = periodRow(draft.id(), 1);
		assertThat(first.get("due_date")).isEqualTo("2026-02-28");
		assertThat((BigDecimal) first.get("principal_amount")).isEqualByComparingTo("1333333.33");
		assertThat((BigDecimal) first.get("interest_amount")).isEqualByComparingTo("240000.00");
		assertThat(first.get("status")).isEqualTo("PENDING");

		// The last period absorbs the rounding residual: 16,000,000 − 11 × 1,333,333.33.
		Map<String, Object> last = periodRow(draft.id(), 12);
		assertThat(last.get("due_date")).isEqualTo("2027-01-31");
		assertThat((BigDecimal) last.get("principal_amount")).isEqualByComparingTo("1333333.37");
		assertThat((BigDecimal) last.get("interest_amount")).isEqualByComparingTo("240000.00");

		List<Installment> schedule = installments.findByContractIdOrderByPeriodNo(draft.id());
		assertThat(schedule).extracting(Installment::getDueDate).isSorted();
		for (int i = 1; i < schedule.size(); i++) {
			assertThat(schedule.get(i).getDueDate()).isAfter(schedule.get(i - 1).getDueDate());
		}
		// Nothing is resolved yet, so outstanding = Σ principal (future interest is not receivable).
		assertThat(schedule).allSatisfy(line -> assertThat(line.getPaidAmount()).isEqualByComparingTo("0.00"));
	}

	@Test
	void activationIsIdempotentOnAnAlreadyActiveContract() {
		ContractResponse draft = createDraft("B1234XY", PLANNED_START);
		ContractResponse first = commands.activate(draft.id(), null);
		List<UUID> installmentIds = installments.findByContractIdOrderByPeriodNo(draft.id()).stream()
				.map(Installment::getId)
				.toList();

		ContractResponse second = commands.activate(draft.id(), null);

		assertThat(second.status()).isEqualTo(ContractStatus.ACTIVE);
		assertThat(second.startDate()).isEqualTo(first.startDate());
		assertThat(second.outstanding()).isEqualByComparingTo("16000000.00");
		// No second schedule, and the contract row itself was not written again.
		assertThat(countInstallments(draft.id())).isEqualTo(12);
		assertThat(installments.findByContractIdOrderByPeriodNo(draft.id()).stream().map(Installment::getId).toList())
				.isEqualTo(installmentIds);
		assertThat(second.version()).isEqualTo(first.version());
	}

	@Test
	void concurrentActivationStillWritesExactlyOneSchedule() throws Exception {
		ContractResponse draft = createDraft("B1234XY", PLANNED_START);
		CountDownLatch startTogether = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
		int successes = 0;
		try {
			List<Future<ContractResponse>> futures = new ArrayList<>();
			for (int attempt = 0; attempt < 2; attempt++) {
				futures.add(pool.submit(() -> {
					startTogether.await(5, TimeUnit.SECONDS);
					return commands.activate(draft.id(), null);
				}));
			}
			startTogether.countDown();
			for (Future<ContractResponse> future : futures) {
				try {
					assertThat(future.get(30, TimeUnit.SECONDS).status()).isEqualTo(ContractStatus.ACTIVE);
					successes++;
				} catch (ExecutionException ex) {
					failures.add(ex.getCause());
				}
			}
		} finally {
			pool.shutdownNow();
		}

		// One side wins; the loser either observes ACTIVE (idempotent branch) or loses on the
		// contract version / the (contract_id, period_no) constraint — never a partial schedule.
		assertThat(successes).isGreaterThanOrEqualTo(1);
		assertThat(failures).allSatisfy(cause -> assertThat(cause)
				.isInstanceOfAny(OptimisticLockingFailureException.class, DataIntegrityViolationException.class));

		assertThat(countInstallments(draft.id())).isEqualTo(12);
		assertThat(jdbc.queryForObject(
				"select count(distinct period_no) from installment where contract_id = ?", Integer.class, draft.id()))
				.isEqualTo(12);
		assertThat(jdbc.queryForObject("select status from contract where id = ?", String.class, draft.id()))
				.isEqualTo("ACTIVE");
		assertThat(jdbc.queryForObject("select to_char(start_date, 'YYYY-MM-DD') from contract where id = ?",
				String.class, draft.id())).isEqualTo("2026-01-31");
	}

	@Test
	void activationSnapshotsTheConfigurationInForceAtActivationNotAtDrafting() {
		ContractResponse draft = createDraft("B1234XY", PLANNED_START);
		// The draft carries the provisional snapshot taken when it was created (Addendum §1.1).
		assertThat(contractGracePeriodDays(draft.id())).isEqualTo(3);
		assertThat(contractPenaltyRate(draft.id())).isEqualByComparingTo("0.0010");

		// Configuration changes between drafting and activation (append-only: new effective date).
		insertConfigurationOverride(SystemParameterKeys.DEFAULT_GRACE_PERIOD_DAYS, "5");
		insertConfigurationOverride(SystemParameterKeys.DEFAULT_PENALTY_RATE_DAILY, "0.0020");

		commands.activate(draft.id(), null);

		// D3: the live contract records the configuration in force when it went live (DM §1.3).
		assertThat(contractGracePeriodDays(draft.id())).isEqualTo(5);
		assertThat(contractPenaltyRate(draft.id())).isEqualByComparingTo("0.0020");
	}

	@Test
	void explicitStartDateOverridesThePlannedDateAndDrivesTheSchedule() {
		ContractResponse draft = createDraft("B1234XY", PLANNED_START);

		ContractResponse activated = commands.activate(draft.id(), LocalDate.of(2026, 2, 10));

		assertThat(activated.startDate()).isEqualTo(LocalDate.of(2026, 2, 10));
		assertThat(activated.plannedStartDate()).isEqualTo(PLANNED_START);
		assertThat(jdbc.queryForObject(
				"select to_char(min(due_date), 'YYYY-MM-DD') from installment where contract_id = ?",
				String.class, draft.id())).isEqualTo("2026-03-10");
	}

	@Test
	void activationWithoutAnyStartDateIsRejectedAndWritesNothing() {
		ContractResponse draft = createDraft("B1234XY", PLANNED_START);
		jdbc.update("update contract set planned_start_date = null where id = ?", draft.id());

		assertThatThrownBy(() -> commands.activate(draft.id(), null))
				.isInstanceOf(ContractStateException.class)
				.hasMessageContaining("start date");

		assertThat(countInstallments(draft.id())).isZero();
		assertThat(jdbc.queryForObject("select status from contract where id = ?", String.class, draft.id()))
				.isEqualTo("DRAFT");
	}

	@Test
	void activationOfAClosedContractIsRejected() {
		ContractResponse draft = createDraft("B1234XY", PLANNED_START);
		commands.activate(draft.id(), null);
		jdbc.update("""
				update contract set status = 'CLOSED', closed_at = clock_timestamp(), closed_reason = 'MATURITY'
				where id = ?
				""", draft.id());

		assertThatThrownBy(() -> commands.activate(draft.id(), null))
				.isInstanceOf(ContractStateException.class)
				.hasMessageContaining("CLOSED");
	}

	@Test
	void activationOfAnUnknownContractIsNotFound() {
		assertThatThrownBy(() -> commands.activate(UUID.randomUUID(), null))
				.isInstanceOf(ContractNotFoundException.class);
	}

	// -------------------------------------------------------------------------------------
	// Fixtures & helpers
	// -------------------------------------------------------------------------------------

	private ContractResponse createDraft(String plateNo, LocalDate plannedStartDate) {
		return commands.create("it-create-key-" + UUID.randomUUID(), new CreateContractRequest(
				new CreateCustomerRequest("Budi Santoso", NIK, PHONE, "Jakarta"),
				new CreateAssetRequest(AssetType.MOTORCYCLE, "Honda", "Beat", null, plateNo),
				new BigDecimal("20000000.00"), new BigDecimal("4000000.00"), 12, InterestScheme.FLAT,
				new BigDecimal("0.0150"), plannedStartDate));
	}

	private long countInstallments(UUID contractId) {
		return jdbc.queryForObject("select count(*) from installment where contract_id = ?", Long.class, contractId);
	}

	/** Sum of one installment column; the column name is a test literal, never request input. */
	private BigDecimal sumOf(UUID contractId, String column) {
		return jdbc.queryForObject("select sum(" + column + ") from installment where contract_id = ?",
				BigDecimal.class, contractId);
	}

	private Map<String, Object> periodRow(UUID contractId, int periodNo) {
		return jdbc.queryForMap("""
				select to_char(due_date, 'YYYY-MM-DD') as due_date, principal_amount, interest_amount, status
				from installment where contract_id = ? and period_no = ?
				""", contractId, periodNo);
	}

	private int contractGracePeriodDays(UUID contractId) {
		return jdbc.queryForObject("select grace_period_days from contract where id = ?", Integer.class, contractId);
	}

	private BigDecimal contractPenaltyRate(UUID contractId) {
		return jdbc.queryForObject("select penalty_rate_daily from contract where id = ?", BigDecimal.class,
				contractId);
	}

	/** Append-only configuration change: a new row effective on the current business date. */
	private void insertConfigurationOverride(String paramKey, String paramValue) {
		jdbc.update("""
				insert into system_parameter (param_key, param_value, effective_date, description,
					created_at, updated_at)
					values (?, ?, ?, 'IT override', clock_timestamp(), clock_timestamp())
				""", paramKey, paramValue, java.sql.Date.valueOf(clock.today()));
	}

	private void truncateDomainTables() {
		// Shared Testcontainers hygiene (same recipe as ContractPersistenceIT): TRUNCATE does not fire
		// the row-level/deferred triggers, and app_user keeps the seeded SYSTEM row. The number counter
		// is included because its row carries created_by/updated_by references to the test actor.
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