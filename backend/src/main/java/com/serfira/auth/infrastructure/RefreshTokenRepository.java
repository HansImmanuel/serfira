package com.serfira.auth.infrastructure;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.serfira.auth.domain.RefreshToken;

/**
 * JPA repository for {@link RefreshToken} (T21a, extended by T21b).
 *
 * <p>{@link #findByTokenHash(String)} looks a presented token up by its SHA-256 digest; the raw
 * value is never stored, so it can never be loaded by value.
 *
 * <p>{@link #revokeIfActive} is the single point that retires a token row (rotation and logout).
 * It is a **conditional** update — {@code WHERE revoked_at IS NULL} — so of two concurrent
 * refreshes presenting the same token exactly one affects a row. The loser gets 0 and must treat
 * the token as replay, which is why revocation is not an entity setter: a read-modify-write would
 * let both callers mint a pair (T21b, ADR-022 addendum D11).
 */
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

	Optional<RefreshToken> findByTokenHash(String tokenHash);

	/**
	 * Revokes a still-active token row, optionally linking it to its replacement.
	 *
	 * @param id            the row to retire
	 * @param replacementId the token that supersedes it, or {@code null} for a logout
	 * @param now           revocation instant from the application clock
	 * @param actorId       the owning {@code app_user.id}
	 * @return 1 when this caller retired the row, 0 when it was already revoked or unknown
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update RefreshToken t
			   set t.revokedAt = :now,
			       t.replacedById = :replacementId,
			       t.updatedAt = :now,
			       t.updatedBy = :actorId
			 where t.id = :id
			   and t.revokedAt is null
			""")
	int revokeIfActive(
			@Param("id") UUID id,
			@Param("replacementId") UUID replacementId,
			@Param("now") OffsetDateTime now,
			@Param("actorId") UUID actorId);
}
