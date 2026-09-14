package com.serfira.contract.domain;

/**
 * Interest calculation scheme of a financing contract (03_DOMAIN_MODEL.md §1.3).
 *
 * <p>Rates are stored and received as monthly decimal fractions (1.5% = {@code 0.0150}).
 */
public enum InterestScheme {

	/** Fixed interest on the initial principal each period. */
	FLAT,

	/** Annuity interest on the declining outstanding balance each period. */
	EFFECTIVE
}