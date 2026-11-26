package com.serfira.settlement.infrastructure;

import com.serfira.settlement.domain.Settlement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Persistence for {@link Settlement} (E2, task T13). One settlement per quote is enforced by the database
 * ({@code uk_settlement_quote_id}, V1) and one per idempotency key by {@code uq_settlement_idempotency}
 * (V1); this repository only persists the executed row.
 */
public interface SettlementRepository extends JpaRepository<Settlement, UUID> {

	/**
	 * {@code Σ cash_received} of the settlements executed in {@code [from, to)}, for the outstanding report's
	 * settlement cash (T36, ADR-026 D6). {@code credit_used} is not cash and is not summed. Served by
	 * {@code idx_settlement_executed_at} (V24); {@code coalesce} yields zero when nothing matches.
	 */
	@Query("""
			select coalesce(sum(s.cashReceived), 0)
			from Settlement s
			where s.executedAt >= :from and s.executedAt < :to
			""")
	BigDecimal sumCashReceivedExecutedBetween(@Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);
}
