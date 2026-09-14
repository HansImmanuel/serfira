package com.serfira.contract.domain;

/**
 * Lifecycle state of a financing contract (03_DOMAIN_MODEL.md §1.3).
 *
 * <p>Transition rule (invariant): {@code DRAFT → ACTIVE → CLOSED/TERMINATED}; a contract can never
 * return to {@code DRAFT} once {@code ACTIVE}. {@code CLOSED} requires {@code closed_reason} to be set.
 */
public enum ContractStatus {

	DRAFT,
	ACTIVE,
	CLOSED,
	TERMINATED
}