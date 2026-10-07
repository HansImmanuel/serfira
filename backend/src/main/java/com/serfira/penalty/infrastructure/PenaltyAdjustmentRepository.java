package com.serfira.penalty.infrastructure;

import com.serfira.penalty.domain.PenaltyAdjustment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Persistence for {@link PenaltyAdjustment}. Append-only in practice — the V1 immutability trigger
 * ({@code trg_penalty_adjustment_immutable}) and the V3/V15 caps are the database backstops; this
 * interface only ever INSERTs and reads.
 *
 * <p>The aggregate query is the {@code Σ adjustment} term of the effective-penalty formula (invariant 9),
 * read by {@code EffectivePenaltyService}. It is JPQL over the entity (not native) now that the entity
 * exists in this module — the ADR-019 D1 replacement for the retired {@code contract} native read.
 */
public interface PenaltyAdjustmentRepository extends JpaRepository<PenaltyAdjustment, UUID> {

	List<PenaltyAdjustment> findByInstallmentIdIn(Collection<UUID> installmentIds);

	/**
	 * Σ adjustment amount per installment, for the installments that have at least one adjustment.
	 *
	 * @param installmentIds installments to total
	 * @return one row per installment that has adjustments; a missing installment means "no adjustments"
	 */
	@Query("""
			select new com.serfira.penalty.infrastructure.InstallmentAdjustmentTotal(
				a.installmentId, sum(a.amount))
			from PenaltyAdjustment a
			where a.installmentId in :installmentIds
			group by a.installmentId
			""")
	List<InstallmentAdjustmentTotal> sumAdjustmentsByInstallmentIds(
			@Param("installmentIds") Collection<UUID> installmentIds);
}
