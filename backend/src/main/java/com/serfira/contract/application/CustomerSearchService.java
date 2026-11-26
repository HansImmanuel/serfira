package com.serfira.contract.application;

import com.serfira.contract.infrastructure.CustomerRepository;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;

/**
 * Find-a-customer by raw identifier (NIK or phone) for operational tracing — the only sanctioned
 * search path (ADR-004, Addendum §9).
 *
 * <p>Raw identifiers are normalized and HMAC-hashed in-memory and the lookup value is what reaches the
 * query; the plaintext never touches SQL, logs, or the URL. The lookup matches the hash under every PII ring key
 * (ADR-025), so a customer written before a key rotation is still found. The database enforces uniqueness of
 * each lookup column per key only: if one person was stored under two key ids (a cross-key duplicate, ADR-025
 * D4), a lookup throws {@link IllegalStateException} with the row count instead of picking one. The HTTP exposure of this service is
 * intentionally deferred until RBAC exists (default-deny, Sprint 6b); callers today are internal services.
 */
@Service
public class CustomerSearchService {

	private final CustomerRepository customers;

	public CustomerSearchService(CustomerRepository customers) {
		this.customers = customers;
	}

	/** Looks up a customer by raw NIK (separators and whitespace allowed). */
	public Optional<CustomerSummary> findByRawNik(String rawNik) {
		Objects.requireNonNull(rawNik, "rawNik");
		return CustomerLookup.byRawNik(customers, rawNik).map(CustomerSummary::from);
	}

	/** Looks up a customer by raw phone (local {@code 0…} and international {@code +62…} forms equivalent). */
	public Optional<CustomerSummary> findByRawPhone(String rawPhone) {
		Objects.requireNonNull(rawPhone, "rawPhone");
		return CustomerLookup.byRawPhone(customers, rawPhone).map(CustomerSummary::from);
	}
}