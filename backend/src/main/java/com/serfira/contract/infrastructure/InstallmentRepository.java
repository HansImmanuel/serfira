package com.serfira.contract.infrastructure;

import com.serfira.contract.domain.Installment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for {@link Installment}. One schedule per contract is enforced by the unique
 * {@code (contract_id, period_no)} constraint.
 *
 * <p>The aggregate queries exist so a page of contracts can be enriched with outstanding amounts
 * without loading every installment row (PRD C-4 "sisa outstanding per kontrak").
 */
public interface InstallmentRepository extends JpaRepository<Installment, UUID> {

	List<Installment> findByContractIdOrderByPeriodNo(UUID contractId);

	long countByContractId(UUID contractId);

	/**
	 * Outstanding totals per contract for a set of contracts, in one query.
	 *
	 * <p>Kept in step with {@code InstallmentBalance}: principal + recognized interest + penalty
	 * (no future unrecognized interest) minus paid + settled + written-off. Penalty adjustments are
	 * intentionally absent until the waiver flow (story E5) exists.
	 */
	@Query("""
			select new com.serfira.contract.infrastructure.ContractInstallmentTotals(
				i.contract.id,
				sum(i.principalAmount + i.recognizedInterestAmount + i.penaltyAmount),
				sum(i.paidAmount + i.settledAmount + i.writtenOffAmount))
			from Installment i
			where i.contract.id in :contractIds
			group by i.contract.id
			""")
	List<ContractInstallmentTotals> sumTotalsByContractIds(@Param("contractIds") Collection<UUID> contractIds);

	/**
	 * Outstanding totals for a single contract. Empty when the contract has no schedule yet (a DRAFT
	 * contract), which is how the API reports {@code outstanding = null}.
	 */
	@Query("""
			select new com.serfira.contract.infrastructure.ContractInstallmentTotals(
				i.contract.id,
				sum(i.principalAmount + i.recognizedInterestAmount + i.penaltyAmount),
				sum(i.paidAmount + i.settledAmount + i.writtenOffAmount))
			from Installment i
			where i.contract.id = :contractId
			group by i.contract.id
			""")
	Optional<ContractInstallmentTotals> sumTotalsByContractId(@Param("contractId") UUID contractId);

	/**
	 * Σ penalty adjustments per installment, for the receivable snapshot handed to other modules
	 * (DM §1.4 invariant 9: effective penalty = {@code penalty_amount − Σ adjustments}).
	 *
	 * <p><b>Temporary read seam (ADR-010):</b> {@code penalty_adjustment} is written by the penalty
	 * module (story E5; the module itself now exists — D1 owns {@code penalty_accrual}), while the
	 * effective penalty is part of the receivable this module exposes — and V3's
	 * {@code assert_payment_allocation_component_caps} computes the very same sum for the very same cap.
	 * Only rows that exist are returned, so the caller treats a missing installment as "no adjustments".
	 * E5 replaces this with the penalty module's own interface.
	 *
	 * <p>Native and mapped explicitly because there is deliberately no {@code PenaltyAdjustment} entity
	 * in this module: the projection is two columns and nothing here is ever written.
	 *
	 * @return rows of {@code [installment_id (UUID), total (BigDecimal)]}, one per installment that has
	 *         adjustments
	 */
	@Query(value = """
			select installment_id, sum(amount)
			from penalty_adjustment
			where installment_id in (:installmentIds)
			group by installment_id
			""", nativeQuery = true)
	List<Object[]> sumPenaltyAdjustmentsByInstallmentIds(
			@Param("installmentIds") Collection<UUID> installmentIds);
}