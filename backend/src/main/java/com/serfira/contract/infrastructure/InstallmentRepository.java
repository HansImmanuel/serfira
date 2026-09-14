package com.serfira.contract.infrastructure;

import com.serfira.contract.domain.Installment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Persistence for {@link Installment}. One schedule per contract is enforced by the unique
 * {@code (contract_id, period_no)} constraint.
 */
public interface InstallmentRepository extends JpaRepository<Installment, UUID> {

	List<Installment> findByContractIdOrderByPeriodNo(UUID contractId);
}