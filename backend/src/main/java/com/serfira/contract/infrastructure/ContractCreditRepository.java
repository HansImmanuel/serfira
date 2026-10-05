package com.serfira.contract.infrastructure;

import com.serfira.contract.domain.ContractCredit;
import com.serfira.contract.domain.ContractCreditStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Persistence for {@link ContractCredit}. One credit per EXCESS allocation is enforced by the database
 * ({@code uk_contract_credit_source}, V1), never by an application pre-check.
 */
public interface ContractCreditRepository extends JpaRepository<ContractCredit, UUID> {

	/** Every credit of a contract, oldest first, for the balance + history read (GET …/credit). */
	List<ContractCredit> findByContractIdOrderByCreatedAtAsc(UUID contractId);

	/**
	 * AVAILABLE credits of a contract, oldest first — the ones an apply call consumes (Addendum §2.1:
	 * oldest credit first keeps the audit trail chronological).
	 */
	List<ContractCredit> findByContractIdAndStatusOrderByCreatedAtAsc(UUID contractId,
			ContractCreditStatus status);
}
