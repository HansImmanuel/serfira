package com.serfira.penalty.infrastructure;

import com.serfira.penalty.domain.PenaltyAccrual;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Persistence for {@link PenaltyAccrual}. One accrual per {@code (installment_id, accrual_date)} is enforced
 * by the unique constraint (DM §1.9, invariant 8); the V1 index on the same columns serves the per-contract
 * read the daily step needs.
 */
public interface PenaltyAccrualRepository extends JpaRepository<PenaltyAccrual, UUID> {

	/**
	 * The accrual rows of a contract's installments, in one query.
	 *
	 * <p>The step only needs the <b>dates</b> that already have a row: a day with a row is never charged
	 * again, and a day without one is still chargeable, which is what lets a day that was skipped (base zero)
	 * be charged later once the base is restored (ADR-012 decision 4).
	 */
	List<PenaltyAccrual> findByInstallmentIdIn(Collection<UUID> installmentIds);
}
