package com.serfira.shared.config;

import com.serfira.shared.audit.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDate;
import java.util.UUID;

/**
 * JPA mapping of {@code system_parameter} (04_GAPS_ADDENDUM.md §1.2–1.3): append-only global
 * configuration, read through {@link SystemParameterService}.
 *
 * <p>Rows are never updated: a configuration change appends a new row with a later
 * {@code effectiveDate}, and the row in force is "latest row with {@code effective_date <= business
 * date}". The entity therefore exposes getters only. Values are stored as strings (that is the
 * documented schema) and parsed by the reader.
 */
@Entity
@Table(name = "system_parameter", uniqueConstraints = {
		@UniqueConstraint(name = "uk_system_parameter", columnNames = {"param_key", "effective_date"})
})
public class SystemParameter extends Auditable {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "param_key", nullable = false, length = 60)
	private String paramKey;

	@Column(name = "param_value", nullable = false, length = 200)
	private String paramValue;

	@Column(name = "effective_date", nullable = false)
	private LocalDate effectiveDate;

	@Column(name = "description", columnDefinition = "text")
	private String description;

	protected SystemParameter() {
		// JPA
	}

	public UUID getId() {
		return id;
	}

	public String getParamKey() {
		return paramKey;
	}

	public String getParamValue() {
		return paramValue;
	}

	public LocalDate getEffectiveDate() {
		return effectiveDate;
	}

	public String getDescription() {
		return description;
	}
}