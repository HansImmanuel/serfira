package com.serfira.contract.infrastructure;

import com.serfira.contract.domain.Customer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for {@link Customer}.
 *
 * <p>The only equality/search paths are the HMAC lookup columns {@code nik_hash} and {@code phone_lookup}
 * (ADR-004) — the AES-GCM ciphertext in {@code nik}/{@code phone} is never queried; uniqueness is
 * enforced by the database on both lookup columns.
 *
 * <p>Production lookups match <b>candidates</b> — the value's hash under every PII ring key (ADR-025) — through
 * the {@code …In} finders, via {@code CustomerLookup}; the single-hash finders remain for tests that hash under
 * one known key.
 */
public interface CustomerRepository extends JpaRepository<Customer, UUID> {

	Optional<Customer> findByNikHash(String nikHash);

	Optional<Customer> findByPhoneLookup(String phoneLookup);

	List<Customer> findByNikHashIn(Collection<String> nikHashes);

	List<Customer> findByPhoneLookupIn(Collection<String> phoneLookups);
}
