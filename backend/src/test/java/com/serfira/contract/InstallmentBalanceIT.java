package com.serfira.contract;

import com.serfira.TestcontainersConfiguration;
import com.serfira.contract.api.ContractResponse;
import com.serfira.contract.api.CreateAssetRequest;
import com.serfira.contract.api.CreateContractRequest;
import com.serfira.contract.api.CreateCustomerRequest;
import com.serfira.contract.application.ContractCommandService;
import com.serfira.contract.domain.AssetType;
import com.serfira.contract.domain.Installment;
import com.serfira.contract.domain.InstallmentBalance;
import com.serfira.contract.domain.InterestScheme;
import com.serfira.contract.infrastructure.InstallmentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B5 — {@link InstallmentBalance} against real persisted amounts (DM §1.4, §3 invariant 13).
 *
 * <p>The resolution amounts are written by the payment/settlement stories, which do not exist yet, so
 * this suite drives them through SQL and reads the row back through the JPA mapping. That keeps the
 * balance definition honest for PARTIALLY_PAID / PAID / OVERDUE / SETTLED / WRITTEN_OFF without
 * inventing mutators on the entity.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class InstallmentBalanceIT {

	private static final String PRINCIPAL = "1333333.33";

	@Autowired
	ContractCommandService commands;

	@Autowired
	InstallmentRepository installments;

	@Autowired
	JdbcTemplate jdbc;

	private UUID contractId;

	@BeforeEach
	void createActivatedContract() {
		truncateDomainTables();
		ContractResponse draft = commands.create("it-balance-key-" + UUID.randomUUID(), new CreateContractRequest(
				new CreateCustomerRequest("Budi Santoso", "3171012501900001", "08123456789", "Jakarta"),
				new CreateAssetRequest(AssetType.MOTORCYCLE, "Honda", "Beat", null, "B1234XY"),
				new BigDecimal("20000000.00"), new BigDecimal("4000000.00"), 12, InterestScheme.FLAT,
				new BigDecimal("0.0150"), LocalDate.of(2026, 1, 31)));
		commands.activate(draft.id(), null);
		contractId = draft.id();
	}

	@AfterEach
	void leaveCleanSharedDatabase() {
		// Leaves no row referencing this suite's actor (another suite deletes app_user rows).
		truncateDomainTables();
	}

@Test
	void partiallyPaidInstallmentOutstandingIsTheUnpaidRecognizedReceivable() {
		// Interest is recognized (billed) at its due date and 500,000 of the obligation is paid.
		updatePeriod(1, "recognized_interest_amount = interest_amount, paid_amount = 500000.00, "
				+ "status = 'PARTIALLY_PAID', paid_at = clock_timestamp()");

		InstallmentBalance balance = balanceOf(1);

		assertThat(balance.recognizedTotal()).isEqualByComparingTo("1573333.33");
		assertThat(balance.resolvedAmount()).isEqualByComparingTo("500000.00");
		assertThat(balance.outstanding()).isEqualByComparingTo("1073333.33");
	}

	@Test
	void fullyPaidInstallmentHasNoOutstanding() {
		updatePeriod(1, "recognized_interest_amount = interest_amount, paid_amount = 1573333.33, "
				+ "status = 'PAID', paid_at = clock_timestamp()");

		InstallmentBalance balance = balanceOf(1);

		assertThat(balance.resolvedAmount()).isEqualByComparingTo("1573333.33");
		assertThat(balance.outstanding()).isEqualByComparingTo("0.00");
	}

	@Test
	void recognizedPenaltyIsPartOfTheReceivableWhileFutureInterestIsNot() {
		// OVERDUE + penalty accrued by the (future) daily job; interest billed, nothing paid yet.
		updatePeriod(2, "recognized_interest_amount = interest_amount, penalty_amount = 10000.00, "
				+ "status = 'OVERDUE'");

		InstallmentBalance balance = balanceOf(2);

		assertThat(balance.recognizedTotal()).isEqualByComparingTo("1583333.33");
		assertThat(balance.outstanding()).isEqualByComparingTo("1583333.33");
		// Period 3 is still untouched: scheduled interest is not receivable before billing.
		assertThat(balanceOf(3).outstanding()).isEqualByComparingTo(PRINCIPAL);
	}

	@Test
	void settledInstallmentResolvesThroughSettledAmountNotPaidAmount() {
		updatePeriod(3, "recognized_interest_amount = interest_amount, settled_amount = 1573333.33, "
				+ "status = 'SETTLED', settled_at = clock_timestamp()");

		InstallmentBalance balance = balanceOf(3);

		assertThat(balance.resolvedAmount()).isEqualByComparingTo("1573333.33");
		assertThat(balance.outstanding()).isEqualByComparingTo("0.00");
	}

	@Test
	void writtenOffInstallmentResolvesThroughWrittenOffAmount() {
		updatePeriod(4, "recognized_interest_amount = interest_amount, written_off_amount = 1573333.33, "
				+ "status = 'WRITTEN_OFF', written_off_at = clock_timestamp()");

		InstallmentBalance balance = balanceOf(4);

		assertThat(balance.resolvedAmount()).isEqualByComparingTo("1573333.33");
		assertThat(balance.outstanding()).isEqualByComparingTo("0.00");
	}

	@Test
	void penaltyAdjustmentsReduceTheReceivableThroughTheDocumentedExtensionPoint() {
		// Story E5 (waiver) will pass the adjustment sum; here it pins the arithmetic on real rows.
		updatePeriod(5, "recognized_interest_amount = interest_amount, penalty_amount = 10000.00, "
				+ "status = 'OVERDUE'");

		InstallmentBalance withAdjustment = InstallmentBalance.of(loadPeriod(5), new BigDecimal("10000.00"));

		assertThat(withAdjustment.recognizedTotal()).isEqualByComparingTo("1573333.33");
		assertThat(withAdjustment.outstanding()).isEqualByComparingTo("1573333.33");
		assertThat(InstallmentBalance.of(loadPeriod(5)).outstanding()).isEqualByComparingTo("1583333.33");
	}

	private InstallmentBalance balanceOf(int periodNo) {
		return InstallmentBalance.of(loadPeriod(periodNo));
	}

	private Installment loadPeriod(int periodNo) {
		List<Installment> schedule = installments.findByContractIdOrderByPeriodNo(contractId);
		return schedule.stream().filter(line -> line.getPeriodNo() == periodNo).findFirst().orElseThrow();
	}

	/** Test-only SQL: the resolution amounts are written by stories that do not exist yet. */
	private void updatePeriod(int periodNo, String assignments) {
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