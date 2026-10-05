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
}
