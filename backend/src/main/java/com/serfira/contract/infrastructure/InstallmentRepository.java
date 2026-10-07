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

	/**
	 * Every installment of every ACTIVE contract, ordered deterministically (contract number, then period),
	 * with its contract fetched so the aging report can read {@code contract_no} and {@code grace_period_days}
	 * without a per-row lazy load (T8). Scope is ACTIVE contracts only (ADR-013 A-7); the caller drops rows
	 * with zero outstanding.
	 */
	@Query("""
			select i from Installment i
			join fetch i.contract c
			where c.status = com.serfira.contract.domain.ContractStatus.ACTIVE
			order by c.contractNo, i.periodNo
			""")
	List<Installment> findActiveContractInstallments();

	/**
	 * Outstanding totals per contract for a set of contracts, in one query.
	 *
	 * <p>Kept in step with {@code InstallmentBalance}: principal + recognized interest + penalty
	 * (no future unrecognized interest) minus paid + settled + written-off. Penalty adjustments are
	 * deliberately omitted here: this per-contract aggregate predates the waiver flow and is not one of
	 * the receivable snapshot / credit-apply call sites that T15 routed through {@code EffectivePenaltyPort}.
	 * Netting adjustments into per-contract totals is a known follow-up (ADR-019; recorded in the T15 note).
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
}