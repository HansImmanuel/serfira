package com.serfira.contract;

import com.serfira.TestcontainersConfiguration;
import com.serfira.contract.domain.Asset;
import com.serfira.contract.domain.AssetType;
import com.serfira.contract.domain.Contract;
import com.serfira.contract.domain.ContractStatus;
import com.serfira.contract.domain.Customer;
import com.serfira.contract.domain.Installment;
import com.serfira.contract.domain.InstallmentStatus;
import com.serfira.contract.domain.InterestScheme;
import com.serfira.contract.infrastructure.AssetRepository;
import com.serfira.contract.infrastructure.ContractRepository;
import com.serfira.contract.infrastructure.CustomerRepository;
import com.serfira.contract.infrastructure.InstallmentRepository;
import com.serfira.shared.audit.AuditContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B1 — proves the JPA entity mappings match the V1 schema (via {@code ddl-auto=validate} on context load)
 * and that the repositories persist/read the contract aggregate against a real PostgreSQL 16.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ContractPersistenceIT {

	@Autowired
	CustomerRepository customers;

	@Autowired
	AssetRepository assets;

	@Autowired
	ContractRepository contracts;

	@Autowired
	InstallmentRepository installments;

	@Autowired
	JdbcTemplate jdbc;

	@BeforeEach
	void cleanDomainTables() {
		// One Testcontainers DB is shared by all cached @SpringBootTest contexts, so other ITs leave
		// committed rows behind (e.g. BaselineSchemaIT payments/settlements referencing contract).
		// TRUNCATE ... CASCADE resets only the schema-owned tables; row-level immutable triggers do not fire.
		jdbc.execute("""
				TRUNCATE TABLE
					refresh_token, idempotency_keys, job_run, reconciliation_exception, outbox_events,
					settlement_credit_application, settlement_allocation, penalty_adjustment, penalty_accrual,
					contract_credit_application, contract_credit, payment_allocation, payment,
					settlement_quote, settlement, installment, journal_line, journal_entry,
					contract, asset, customer
				CASCADE""");
	}

	@Test
	@Transactional
	void persistsAndReadsContractAggregateWithSchedule() {
		Customer customer = customers.saveAndFlush(new Customer(
				"Budi Santoso", "3171012501900001", "h".repeat(64), "08123456789", "Jakarta"));
		Asset asset = assets.saveAndFlush(new Asset(
				AssetType.MOTORCYCLE, "Honda", "Beat", null, "B1234XY"));

		Contract contract = contracts.saveAndFlush(new Contract(
				"MF-202609-0001", customer, asset,
				new BigDecimal("20000000.00"), new BigDecimal("16000000.00"), new BigDecimal("4000000.00"),
				12, InterestScheme.FLAT, new BigDecimal("0.0150"), 3, new BigDecimal("0.0010")));

		for (int period = 1; period <= 12; period++) {
			installments.saveAndFlush(new Installment(
					contract, period, LocalDate.of(2026, 1, 31).plusMonths(period),
					new BigDecimal("1333333.33"), new BigDecimal("240000.00")));
		}

		Contract reloaded = contracts.findByContractNo("MF-202609-0001").orElseThrow();
		assertThat(reloaded.getCustomer().getFullName()).isEqualTo("Budi Santoso");
		assertThat(reloaded.getAsset().getPlateNo()).isEqualTo("B1234XY");
		assertThat(reloaded.getStatus()).isEqualTo(ContractStatus.DRAFT);
		assertThat(reloaded.getStartDate()).isNull();
		assertThat(reloaded.getPrincipal()).isEqualByComparingTo("16000000.00");
		assertThat(reloaded.getCreatedBy()).isEqualTo(AuditContext.SYSTEM_USER_ID);
		assertThat(reloaded.getVersion()).isZero();

		List<Installment> schedule = installments.findByContractIdOrderByPeriodNo(contract.getId());
		assertThat(schedule).hasSize(12);
		assertThat(schedule).extracting(Installment::getPeriodNo).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);
		assertThat(schedule).allSatisfy(line -> {
			assertThat(line.getStatus()).isEqualTo(InstallmentStatus.PENDING);
			assertThat(line.getPaidAmount()).isEqualByComparingTo("0.00");
			assertThat(line.getPrincipalAmount()).isEqualByComparingTo("1333333.33");
			assertThat(line.getInterestAmount()).isEqualByComparingTo("240000.00");
		});
		assertThat(schedule.get(1).getDueDate()).isEqualTo(LocalDate.of(2026, 3, 31));
	}

	@Test
	void duplicatePeriodNumberIsRejectedByDatabase() {
		Customer customer = customers.saveAndFlush(new Customer(
				"Test One", "3201010101010001", "x".repeat(64), null, null));
		Asset asset = assets.saveAndFlush(new Asset(AssetType.CAR, "Toyota", "Avanza", null, null));
		Contract contract = contracts.saveAndFlush(new Contract(
				"MF-202609-0002", customer, asset,
				new BigDecimal("1000.00"), new BigDecimal("900.00"), new BigDecimal("100.00"),
				12, InterestScheme.FLAT, new BigDecimal("0.0150"), 3, new BigDecimal("0.0010")));

		installments.saveAndFlush(new Installment(
				contract, 1, LocalDate.of(2026, 2, 28), new BigDecimal("75.00"), new BigDecimal("11.25")));
		assertThatThrownBy(() -> installments.saveAndFlush(new Installment(
				contract, 1, LocalDate.of(2026, 2, 28), new BigDecimal("1.00"), new BigDecimal("1.00"))))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void duplicateNikHashIsRejectedByDatabase() {
		customers.saveAndFlush(new Customer(
				"First", "3171012501900001", "same-hash", null, null));
		assertThatThrownBy(() -> customers.saveAndFlush(new Customer(
				"Second", "3171012501900002", "same-hash", null, null)))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void contractNumberMustBeUnique() {
		Customer customer = new Customer("Test Two", "3201010101010002", "y".repeat(64), null, null);
		customers.saveAndFlush(customer);
		Asset asset = assets.saveAndFlush(new Asset(AssetType.OTHER, "Samsung", "TV", null, null));

		Contract first = new Contract("MF-202609-0003", customer, asset,
				new BigDecimal("1000.00"), new BigDecimal("900.00"), new BigDecimal("100.00"),
				12, InterestScheme.FLAT, new BigDecimal("0.0150"), 3, new BigDecimal("0.0010"));
		contracts.saveAndFlush(first);

		Contract duplicate = new Contract("MF-202609-0003", customer, asset,
				new BigDecimal("500.00"), new BigDecimal("450.00"), new BigDecimal("50.00"),
				12, InterestScheme.EFFECTIVE, new BigDecimal("0.0075"), 3, new BigDecimal("0.0010"));
		assertThatThrownBy(() -> contracts.saveAndFlush(duplicate))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void schemaEnforcesAssetPriceInvariant() {
		Customer customer = customers.saveAndFlush(new Customer("Test Three", "3201010101010003", "z".repeat(64), null, null));
		Asset asset = assets.saveAndFlush(new Asset(AssetType.MOTORCYCLE, "Yamaha", "Nmax", null, null));

		// asset_price = 5,000,000 but principal + down_payment = 5,100,000 → CHECK violation.
		Contract broken = new Contract("MF-202609-0004", customer, asset,
				new BigDecimal("5000000.00"), new BigDecimal("5000000.00"), new BigDecimal("100000.00"),
				12, InterestScheme.FLAT, new BigDecimal("0.0150"), 3, new BigDecimal("0.0010"));
		assertThatThrownBy(() -> contracts.saveAndFlush(broken))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void contractLookupByUnknownNumberIsEmpty() {
		assertThat(contracts.findByContractNo("MF-209912-9999")).isEmpty();
	}
}