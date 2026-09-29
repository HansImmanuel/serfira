package com.serfira.contract.application;

import java.util.List;
import java.util.UUID;

/**
 * Contract-owned read seam for background servicing jobs.
 *
 * <p>Jobs may iterate ACTIVE contract ids through this interface, but may not query the contract module's
 * repository or tables directly. The status probe closes the race between listing and processing: a
 * contract that becomes non-ACTIVE before its turn is skipped rather than reported as failed.
 */
public interface ActiveContractListingPort {

	/** Returns the ids of contracts that are ACTIVE when the list is read, ordered deterministically. */
	List<UUID> findActiveContractIds();

	/** Returns whether the contract still exists and is ACTIVE when checked. */
	boolean isActive(UUID contractId);
}
