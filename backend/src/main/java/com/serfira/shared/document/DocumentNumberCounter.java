package com.serfira.shared.document;

import com.serfira.shared.audit.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.UUID;

/**
 * JPA mapping of {@code document_number_counter} (03_DOMAIN_MODEL.md §1.14). One row per
 * (type, period) period, incremented transactionally under a row lock — never in memory.
 * Numbering is not gapless: uniqueness is guaranteed by the unique counter_key constraint.
 */
@Entity
@Table(name = "document_number_counter", uniqueConstraints = {
		@UniqueConstraint(name = "uk_document_number_counter_key", columnNames = "counter_key")
})
public class DocumentNumberCounter extends Auditable {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "counter_key", nullable = false, length = 80)
	private String counterKey;

	@Column(name = "last_value", nullable = false)
	private int lastValue;

	protected DocumentNumberCounter() {
		// JPA
	}

	public DocumentNumberCounter(String counterKey) {
		this.counterKey = counterKey;
	}

	/** Returns the current sequence value after incrementing (called under the row lock). */
	int nextValue() {
		lastValue = lastValue + 1;
		return lastValue;
	}

	public UUID getId() {
		return id;
	}

	public String getCounterKey() {
		return counterKey;
	}

	public int getLastValue() {
		return lastValue;
	}
}