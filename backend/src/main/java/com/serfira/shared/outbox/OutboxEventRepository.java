package com.serfira.shared.outbox;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

	List<OutboxEvent> findByAggregateIdOrderByCreatedAtAscIdAsc(UUID aggregateId);
}
