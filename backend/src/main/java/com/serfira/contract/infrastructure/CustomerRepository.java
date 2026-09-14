package com.serfira.contract.infrastructure;

import com.serfira.contract.domain.Customer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for {@link Customer}. Uniqueness of {@code nik_hash} is enforced by the database.
 */
public interface CustomerRepository extends JpaRepository<Customer, UUID> {

	Optional<Customer> findByNikHash(String nikHash);
}