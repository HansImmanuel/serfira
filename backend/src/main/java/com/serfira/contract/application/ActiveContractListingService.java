package com.serfira.contract.application;

import com.serfira.contract.domain.ContractStatus;
import com.serfira.contract.infrastructure.ContractRepository;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Contract-module implementation of the background-servicing read seam. */
@Service
@Transactional(readOnly = true)
public class ActiveContractListingService implements ActiveContractListingPort {

	private final ContractRepository contracts;

	public ActiveContractListingService(ContractRepository contracts) {
		this.contracts = contracts;
	}

	@Override
	public List<UUID> findActiveContractIdsAfter(UUID afterId, int limit) {
		Objects.requireNonNull(afterId, "afterId");
		if (limit < 1) {
			throw new IllegalArgumentException("limit must be positive");
		}
		return contracts.findActiveContractIdsAfter(ContractStatus.ACTIVE, afterId, Limit.of(limit));
	}

	@Override
	public boolean isActive(UUID contractId) {
		Objects.requireNonNull(contractId, "contractId");
		return contracts.existsByIdAndStatus(contractId, ContractStatus.ACTIVE);
	}
}
