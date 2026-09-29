package com.serfira;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies V10 upgrades a populated V9 database, including Jakarta business-date conversion. */
@Testcontainers
class JobRunMigrationIT {

	@Container
	static final PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:16"));

	@Test
	void v10BackfillsExistingJobRunsFromStartedAtInTheBusinessZone() {
		Flyway.configure()
				.dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
				.target("9")
				.load()
				.migrate();
		JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
				postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
		// 18:30 UTC is already the following business date in Asia/Jakarta.
		jdbc.update("""
				insert into job_run (job_name, started_at, created_at, updated_at)
				values ('penalty-accrual', timestamptz '2026-03-04 18:30:00+00',
				        timestamptz '2026-03-04 18:30:00+00', timestamptz '2026-03-04 18:30:00+00')
				""");

		Flyway.configure()
				.dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
				.load()
				.migrate();

		assertThat(jdbc.queryForObject("select business_date from job_run", LocalDate.class))
				.isEqualTo(LocalDate.of(2026, 3, 5));
		assertThat(jdbc.queryForObject("""
				select is_nullable from information_schema.columns
				where table_schema = 'public' and table_name = 'job_run' and column_name = 'business_date'
				""", String.class)).isEqualTo("NO");
	}
}
