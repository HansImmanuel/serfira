package com.serfira.contract.domain;

/**
 * Resolution state of an installment (03_DOMAIN_MODEL.md §1.4, PRD §3).
 *
 * <p>{@code SETTLED} and {@code WRITTEN_OFF} are non-payment resolutions; {@code PAID} means cash was
 * received. {@code OVERDUE} is the payment-ages state assigned by the penalty/aging job.
 */
public enum InstallmentStatus {

	PENDING,
	PARTIALLY_PAID,
	PAID,
	OVERDUE,
	SETTLED,
	WRITTEN_OFF
}