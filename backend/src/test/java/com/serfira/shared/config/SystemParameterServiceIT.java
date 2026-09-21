package com.serfira.shared.config;

import com.serfira.TestcontainersConfiguration;
import com.serfira.shared.clock.Clock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B5 — proves {@link SystemParameterService} resolves the row in force (Addendum §1.2–1.3) against a
 * real PostgreSQL 16: append-only precedence, future-dated rows ignored, and configuration defects
 * failing loudly as server errors rather than silently defaulting.
 *
 * <p>Rows inserted here use a dedicated {@code IT_} key and are removed afterwards: the shared
 * Testcontainers database keeps the V1 seeds, and {@code BaselineSchemaIT} asserts their exact count.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SystemParameterServiceIT {

	private static final String TEST_KEY = "IT_PRECEDENCE_TEST";

	@Autowired
	SystemParameterService systemParameters;

	@Autowired
	Clock clock;

	@Autowired
	JdbcTemplate jdbc;

	@BeforeEach
	void removeTestRowsBefore() {
		removeTestRows();
	}

	@AfterEach
	void removeTestRows() {
		jdbc.update("delete from system_parameter where param_key = ?", TEST_KEY);
	}

	@Test
	void seededPortfolioValuesResolveThroughTheService() {
		assertThat(systemParameters.requireInt(SystemParameterKeys.DEFAULT_GRACE_PERIOD_DAYS)).isEqualTo(3);
		assertThat(systemParameters.requireDecimal(SystemParameterKeys.DEFAULT_PENALTY_RATE_DAILY))
				.isEqualByComparingTo("0.0010");
		assertThat(systemParameters.requireInt(SystemParameterKeys.IDEMPOTENCY_KEY_RETENTION_DAYS)).isEqualTo(7);
	}

	@Test
	void latestEffectiveRowWinsAndTheOlderRowSurvives() {
		LocalDate today = clock.today();
		insert(TEST_KEY, "10", today.minusDays(10));
		insert(TEST_KEY, "20", today);

		assertThat(systemParameters.requireInt(TEST_KEY)).isEqualTo(20);
		// Append-only: reading never rewrites configuration history.
		assertThat(countRows(TEST_KEY)).isEqualTo(2L);
	}

	@Test
	void futureDatedRowIsIgnoredUntilItsDateArrives() {
		LocalDate today = clock.today();
		insert(TEST_KEY, "30", today);
		insert(TEST_KEY, "999", today.plusYears(5));

		assertThat(systemParameters.requireInt(TEST_KEY)).isEqualTo(30);
	}

	@Test
	void businessDateOverloadReadsTheRowInForceOnThatDate() {
		LocalDate today = clock.today();
		insert(TEST_KEY, "1", today.minusDays(20));
		insert(TEST_KEY, "2", today.minusDays(10));

		assertThat(systemParameters.requireInt(TEST_KEY, today.minusDays(15))).isEqualTo(1);
		assertThat(systemParameters.requireInt(TEST_KEY, today.minusDays(5))).isEqualTo(2);
	}

	@Test
	void decimalValuesKeepTheirDocumentedScale() {
		insert(TEST_KEY, "0.0150", clock.today());

		BigDecimal value = systemParameters.requireDecimal(TEST_KEY);

		assertThat(value).isEqualByComparingTo("0.0150");
		assertThat(value.scale()).isEqualTo(4);
	}

	@Test
	void unknownKeyIsAConfigurationDefectNotAClientError() {
		assertThatThrownBy(() -> systemParameters.requireString("IT_DOES_NOT_EXIST"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("IT_DOES_NOT_EXIST");
	}

	@Test
	void unparsableValueIsAConfigurationDefect() {
		insert(TEST_KEY, "not-a-number", clock.today());

		assertThatThrownBy(() -> systemParameters.requireInt(TEST_KEY))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining(TEST_KEY);
		assertThatThrownBy(() -> systemParameters.requireDecimal(TEST_KEY))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining(TEST_KEY);
	}

	private void insert(String paramKey, String paramValue, LocalDate effectiveDate) {
		jdbc.update("""
				insert into system_parameter (param_key, param_value, effective_date, description,
					created_at, updated_at)
					values (?, ?, ?, 'IT row', clock_timestamp(), clock_timestamp())
				""", paramKey, paramValue, java.sql.Date.valueOf(effectiveDate));
	}

	private long countRows(String paramKey) {
		return jdbc.queryForObject("select count(*) from system_parameter where param_key = ?", Long.class, paramKey);
	}
}