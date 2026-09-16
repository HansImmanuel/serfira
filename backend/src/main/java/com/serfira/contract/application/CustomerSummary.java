package com.serfira.contract.application;

import com.serfira.contract.domain.Customer;
import com.serfira.shared.security.PiiMasker;

import java.util.UUID;

/**
 * Masked customer projection for API responses (TECH SPEC 2.2 - entities are never exposed).
 *
 * <p>PII minimization policy (ADR-004 / review H-3): {@code nik} and {@code phone} are masked
 * at the projection, so plaintext never crosses the API boundary. A role-based full-value
 * variant for {@code ADMIN_OPERASIONAL}/{@code FINANCE} is deferred to the RBAC story
 * (Sprint 6b, Addendum 9) and must be an explicitly separate projection.
 */
public record CustomerSummary(UUID id, String fullName, String nik, String phone, String address) {

public static CustomerSummary from(Customer customer) {
return new CustomerSummary(
customer.getId(),
customer.getFullName(),
PiiMasker.maskNik(customer.getNik()),
PiiMasker.maskPhone(customer.getPhone()),
customer.getAddress());
}
}