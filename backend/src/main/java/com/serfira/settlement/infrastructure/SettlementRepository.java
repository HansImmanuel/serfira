package com.serfira.settlement.infrastructure;

import com.serfira.settlement.domain.Settlement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Persistence for {@link Settlement} (E2, task T13). One settlement per quote is enforced by the database
 * ({@code uk_settlement_quote_id}, V1) and one per idempotency key by {@code uq_settlement_idempotency}
 * (V1); this repository only persists the executed row.
 */
public interface SettlementRepository extends JpaRepository<Settlement, UUID> {
}
