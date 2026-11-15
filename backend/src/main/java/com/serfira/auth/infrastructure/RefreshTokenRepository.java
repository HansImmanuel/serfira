package com.serfira.auth.infrastructure;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.serfira.auth.domain.RefreshToken;

/**
 * JPA repository for {@link RefreshToken} (T21a).
 *
 * <p>{@link #findByTokenHash(String)} is the lookup T21b's rotation path uses; T21a only inserts.
 */
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

	Optional<RefreshToken> findByTokenHash(String tokenHash);
}
