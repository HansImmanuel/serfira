package com.serfira.contract.application;

import com.serfira.contract.domain.Customer;
import com.serfira.contract.infrastructure.CustomerRepository;
import com.serfira.shared.security.PiiHasher;
import java.util.List;
import java.util.Optional;

/**
 * Customer lookups by PII candidates (ADR-025): a raw NIK or phone matches the row whose lookup column equals its
 * hash under <i>any</i> ring key, so a customer hashed under an older key is still found.
 *
 * <p>Each lookup column is unique per key, so two matching rows mean one person stored under two key ids — a
 * state the candidate checks at create prevent and the T28b pre-flight refuses. That fails loudly (count only,
 * never values) instead of picking a row. The check lives here, not in a repository default method, so the
 * repository proxy's exception translation does not rewrap it.
 */
final class CustomerLookup {

	private CustomerLookup() {
	}

	static Optional<Customer> byNik(CustomerRepository customers, List<String> nikCandidates) {
		return atMostOne(customers.findByNikHashIn(nikCandidates));
	}

	static Optional<Customer> byPhone(CustomerRepository customers, List<String> phoneCandidates) {
		return atMostOne(customers.findByPhoneLookupIn(phoneCandidates));
	}

	static Optional<Customer> byRawNik(CustomerRepository customers, String rawNik) {
		return byNik(customers, PiiHasher.nikCandidates(rawNik));
	}

	static Optional<Customer> byRawPhone(CustomerRepository customers, String rawPhone) {
		return byPhone(customers, PiiHasher.phoneCandidates(rawPhone));
	}

	private static Optional<Customer> atMostOne(List<Customer> matches) {
		if (matches.size() > 1) {
			throw new IllegalStateException("customer lookup matched " + matches.size() + " rows across PII key ids");
		}
		return matches.stream().findFirst();
	}
}
