package com.serfira.contract.application;

import java.util.UUID;

/**
 * Existence check on a contract, for modules that serve a read keyed by contract id but must not import
 * contract internals (the {@code payment} module's history read, T39). {@code contract} never depends on
 * those modules; they depend on this port.
 */
public interface ContractLookupPort {

	/**
	 * @throws ContractNotFoundException when no contract has this id (404 {@code CONTRACT_NOT_FOUND})
	 */
	void requireExists(UUID contractId);
}
