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

	/**
	 * Returns up to {@code limit} ACTIVE contract ids with id strictly greater than {@code afterId}, ordered
	 * by id ascending (keyset/seek paging for the daily servicing job, CR-08). The first page passes the
	 * all-zero UUID sentinel {@code 00000000-0000-0000-0000-000000000000}, which is strictly less than every
	 * generated id, so the query needs no untyped {@code null} bind (avoids the X-13 {@code 42P18} failure).
	 * A page shorter than {@code limit} is the last page.
	 */
	List<UUID> findActiveContractIdsAfter(UUID afterId, int limit);

	/** Returns whether the contract still exists and is ACTIVE when checked. */
	boolean isActive(UUID contractId);
}
