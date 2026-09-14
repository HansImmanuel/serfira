package com.serfira.contract.application;

import com.serfira.contract.infrastructure.CustomerRepository;
import com.serfira.shared.security.PiiHasher;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;

/**
 * Find-a-customer by raw identifier (NIK or phone) for operational tracing — the only sanctioned
 * search path (ADR-004, Addendum §9).
 *
 * <p>Raw identifiers are normalized and HMAC-hashed in-memory and the lookup value is what reaches the
 * query; the plaintext never touches SQL, logs, or the URL. Uniqueness of both lookup columns is enforced
 * by the database, so each lookup returns at most one customer. The HTTP exposure of this service is
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
		return customers.findByNikHash(PiiHasher.hashNik(rawNik)).map(CustomerSummary::from);
	}

	/** Looks up a customer by raw phone (local {@code 0…} and international {@code +62…} forms equivalent). */
	public Optional<CustomerSummary> findByRawPhone(String rawPhone) {
		Objects.requireNonNull(rawPhone, "rawPhone");
		return customers.findByPhoneLookup(PiiHasher.hashPhone(rawPhone)).map(CustomerSummary::from);
	}
}