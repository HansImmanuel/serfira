package com.serfira.contract.domain;

/**
 * Reason a contract was closed (03_DOMAIN_MODEL.md §1.3). {@code MATURITY} = all installments resolved;
 * {@code SETTLEMENT} = early settlement executed. Set together with {@code closed_at}.
 */
public enum ClosedReason {

	MATURITY,
	SETTLEMENT
}