package com.serfira.settlement.infrastructure;

import com.serfira.settlement.domain.SettlementCreditApplication;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Persistence for {@link SettlementCreditApplication} (E2, task T13). One row per consumed source credit;
 * the {@code uk_settlement_credit_application (settlement_id, contract_credit_id)} unique constraint (V1)
 * keeps a source from being recorded twice for one settlement.
 */
public interface SettlementCreditApplicationRepository extends JpaRepository<SettlementCreditApplication, UUID> {
}
