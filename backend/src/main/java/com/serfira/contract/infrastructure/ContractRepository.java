package com.serfira.contract.infrastructure;

import com.serfira.contract.domain.Contract;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for {@link Contract}. Uniqueness of {@code contract_no} is enforced by the database.
 */
public interface ContractRepository extends JpaRepository<Contract, UUID> {

	Optional<Contract> findByContractNo(String contractNo);
}