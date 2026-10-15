package com.serfira.penalty.infrastructure;

import com.serfira.penalty.domain.consistency.ReconciliationException;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Persistence for {@link ReconciliationException}: the daily consistency check only inserts drift rows
 * (F1, task T17). The {@code status} CHECK and the audit columns are enforced by the database (V1).
 */
public interface ReconciliationExceptionRepository extends JpaRepository<ReconciliationException, UUID> {
}
