package com.serfira.shared.document;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@code document_number_counter}.
 *
 * <p>Concurrency-safe by design: readers take a {@code SELECT … FOR UPDATE} row lock inside the
 * caller's transaction, and the period row is created with {@code INSERT … ON CONFLICT DO NOTHING}
 * so racing generators for a brand-new period serialize on the unique counter_key instead of
 * failing with a duplicate key.
 */
public interface DocumentNumberCounterRepository extends JpaRepository<DocumentNumberCounter, UUID> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select c from DocumentNumberCounter c where c.counterKey = :counterKey")
	Optional<DocumentNumberCounter> findForUpdateByCounterKey(@Param("counterKey") String counterKey);

	@Modifying
	@Query(value = """
			INSERT INTO document_number_counter (id, counter_key, last_value, created_at, updated_at)
			VALUES (:id, :counterKey, 0, :now, :now)
			ON CONFLICT (counter_key) DO NOTHING
			""", nativeQuery = true)
	void insertIfAbsent(@Param("id") UUID id, @Param("counterKey") String counterKey, @Param("now") OffsetDateTime now);
}