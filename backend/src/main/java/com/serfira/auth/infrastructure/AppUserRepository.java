package com.serfira.auth.infrastructure;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.serfira.auth.domain.AppUser;

/**
 * JPA repository for {@link AppUser} (T21a).
 *
 * <p>Lockout bookkeeping is done on the loaded entity and flushed by the caller's transaction
 * (T21b): a login runs inside one transaction that either persists the failure or mints tokens.
 */
public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

	Optional<AppUser> findByUsername(String username);
}
