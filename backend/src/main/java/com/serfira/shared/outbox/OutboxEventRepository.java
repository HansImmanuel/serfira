package com.serfira.shared.outbox;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

	List<OutboxEvent> findByAggregateIdOrderByCreatedAtAscIdAsc(UUID aggregateId);

	/**
	 * Locks and returns up to {@code limit} {@code PENDING} rows in publish order (ADR-027). {@code SKIP LOCKED}
	 * makes a concurrent claimer take the next rows instead of waiting for, or re-reading, these.
	 */
	@Query(value = """
			select * from outbox_events where status = 'PENDING'
			order by created_at, id
			limit :limit
			for update skip locked""", nativeQuery = true)
	List<OutboxEvent> claimPending(@Param("limit") int limit);
}
