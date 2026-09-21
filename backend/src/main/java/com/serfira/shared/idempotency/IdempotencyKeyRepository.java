package com.serfira.shared.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for {@link IdempotencyKey}.
 *
 * <p>{@link #claim} is the concurrency control point: {@code INSERT … ON CONFLICT DO NOTHING} makes
 * racing retries serialize on the unique {@code (endpoint, key)} constraint — a duplicate insert
 * blocks until the claiming transaction finishes and then inserts nothing, so the caller can read
 * the committed outcome instead of executing the mutation twice. The claim lives in the caller's
 * transaction, so a failed mutation rolls the claim back and a corrected retry may proceed.
 */
public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, UUID> {

	@Modifying
	@Query(value = """
			INSERT INTO idempotency_keys
				(id, key, endpoint, request_hash, status, expires_at, created_at, created_by, updated_at, updated_by)
			VALUES (:id, :key, :endpoint, :requestHash, :status, :expiresAt, :now, :actor, :now, :actor)
			ON CONFLICT (endpoint, key) DO NOTHING
			""", nativeQuery = true)
	int claim(@Param("id") UUID id,
			@Param("key") String key,
			@Param("endpoint") String endpoint,
			@Param("requestHash") String requestHash,
			@Param("status") String status,
			@Param("expiresAt") OffsetDateTime expiresAt,
			@Param("now") OffsetDateTime now,
			@Param("actor") UUID actor);

	Optional<IdempotencyKey> findByEndpointAndKey(String endpoint, String key);
}