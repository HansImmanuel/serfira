package com.serfira.settlement.infrastructure;

import com.serfira.settlement.domain.SettlementAllocation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Persistence for {@link SettlementAllocation} (E2, task T13). Rows are immutable (V1
 * {@code trg_settlement_allocation_immutable}); this repository only appends them.
 */
public interface SettlementAllocationRepository extends JpaRepository<SettlementAllocation, UUID> {
}
