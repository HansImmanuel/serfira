package com.serfira.auth.infrastructure;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.serfira.auth.domain.AppUser;

/**
 * JPA repository for {@link AppUser} (T21a).
 */
public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

	Optional<AppUser> findByUsername(String username);
}
