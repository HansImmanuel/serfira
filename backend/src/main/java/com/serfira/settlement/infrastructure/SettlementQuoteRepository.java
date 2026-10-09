package com.serfira.settlement.infrastructure;

import com.serfira.settlement.domain.SettlementQuote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Persistence for {@link SettlementQuote} (E1, task T12). The quote's component columns are immutable at
 * the database level (V5 {@code trg_settlement_quote_mutability}); this repository only persists a new
 * QUOTED row — T13 loads and transitions it.
 */
public interface SettlementQuoteRepository extends JpaRepository<SettlementQuote, UUID> {
}
