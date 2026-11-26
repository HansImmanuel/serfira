package com.serfira.contract.infrastructure;

import java.util.UUID;

/**
 * Spring Data projection of one contract's customer name (T37): the aging report's per-contract row shows the
 * customer's {@code full_name}, which is stored unencrypted (Addendum §9, ADR-004). Selecting the two scalars
 * keeps the read from hydrating {@code Customer}, so NIK and phone are never decrypted just to label a row.
 */
public interface ContractCustomerName {

	UUID getContractId();

	String getFullName();
}
