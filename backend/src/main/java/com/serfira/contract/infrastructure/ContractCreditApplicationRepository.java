package com.serfira.contract.infrastructure;

import com.serfira.contract.domain.ContractCreditApplication;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Persistence for {@link ContractCreditApplication}. The {@code Σ applications <= credit.amount} cap is a
 * DB-level deferred trigger (V14), not an application check (invariant 11, task T14).
 */
public interface ContractCreditApplicationRepository extends JpaRepository<ContractCreditApplication, UUID> {

	/** Applications for a set of credits, oldest first — the history half of GET …/credit. */
	List<ContractCreditApplication> findByCreditIdInOrderByAppliedAtAsc(Collection<UUID> creditIds);

	/**
	 * Σ applied amount per credit, for the available-balance computation (available = {@code amount −
	 * applied}). Only credits that have at least one application are returned; a credit absent from the
	 * result has an applied total of zero.
	 */
	@Query("""
			select new com.serfira.contract.infrastructure.CreditAppliedTotal(
				a.creditId, sum(a.amount))
			from ContractCreditApplication a
			where a.creditId in :creditIds
			group by a.creditId
			""")
	List<CreditAppliedTotal> sumAppliedByCreditIds(@Param("creditIds") Collection<UUID> creditIds);

	/**
	 * Σ applied amount per installment, for a set of installments (E4 void, task T16, ADR-020 D1.3). A void
	 * recomputes an installment's {@code paid_amount} from the remaining POSTED <b>payments</b>, but credit
	 * applied to that installment through {@code contract_credit_application} (task T14) also resolved part
	 * of it and survives the void. This sum lets the recompute add the credit-funded amount back so a void
	 * does not drop surviving credit resolution and wrongly inflate the outstanding balance.
	 *
	 * <p>Only installments with at least one application are returned; an installment absent from the result
	 * has a credit-funded total of zero.
	 */
	@Query("""
			select new com.serfira.contract.infrastructure.CreditResolvedInstallmentTotal(
				a.installmentId, sum(a.amount), min(a.appliedAt))
			from ContractCreditApplication a
			where a.installmentId in :installmentIds
			group by a.installmentId
			""")
	List<CreditResolvedInstallmentTotal> sumAppliedByInstallmentIds(
			@Param("installmentIds") Collection<UUID> installmentIds);
}
