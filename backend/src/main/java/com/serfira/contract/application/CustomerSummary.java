package com.serfira.contract.application;

import com.serfira.contract.domain.Customer;

import java.util.UUID;

/**
 * Mask-ready customer projection for API responses (TECH SPEC §2.2 — entities are never exposed).
 * Role-based masking of {@code nik} is applied at the controller boundary later (Addendum §9) once
 * RBAC lands.
 */
public record CustomerSummary(UUID id, String fullName, String nik, String phone, String address) {

	public static CustomerSummary from(Customer customer) {
		return new CustomerSummary(
				customer.getId(),
				customer.getFullName(),
				customer.getNik(),
				customer.getPhone(),
				customer.getAddress());
	}
}