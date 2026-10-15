package com.serfira.contract.infrastructure;

import com.serfira.contract.domain.ContractCredit;
import com.serfira.contract.domain.ContractCreditStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
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

	/**
	 * The credits a set of EXCESS {@code payment_allocation} ids booked, each row locked
	 * {@code FOR UPDATE} (E4, task T16, ADR-020 D4). A payment void takes the lock before it reads the
	 * credit's available balance, so a concurrent credit-apply or settlement cannot consume the credit
	 * between the eligibility check and the reversal. {@code uk_contract_credit_source} means at most one
	 * credit per allocation.
	 *
	 * @param sourceAllocationIds the EXCESS allocation ids whose credits to lock and load
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select c from ContractCredit c where c.sourcePaymentAllocationId in :sourceAllocationIds")
	List<ContractCredit> findBySourcePaymentAllocationIdInForUpdate(
			@Param("sourceAllocationIds") Collection<UUID> sourceAllocationIds);
}
