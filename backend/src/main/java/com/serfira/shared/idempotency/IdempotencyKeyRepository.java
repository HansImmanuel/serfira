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
 *
 * <p>{@link #reclaimExpired} is the retention half of the same mechanism: a row whose
 * {@code expires_at} has passed may be claimed again, so an old key cannot block a new request
 * indefinitely. Physical cleanup of expired rows stays a separate job (story F4).
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

	/**
	 * Takes over a row whose retention window has already elapsed, so the key becomes usable again
	 * instead of replaying a stale response or reporting a conflict forever (ADR-007 decision 9).
	 *
	 * <p>The row is reused rather than deleted: {@code (endpoint, key)} stays unique and the earlier
	 * claim's {@code created_at}/{@code created_by} are preserved, while {@code updated_*}, the
	 * fingerprint and the expiry move to the new claim. A racing retry blocks on the row lock, then
	 * re-reads the refreshed {@code expires_at} and falls through to the normal replay path.
	 *
	 * @return {@code 1} when this call took the claim over, {@code 0} when the row is missing or is
	 *         still inside its retention window
	 */
	@Modifying
	@Query(value = """
			UPDATE idempotency_keys
			   SET request_hash = :requestHash,
			       response_json = NULL,
			       status = :status,
			       expires_at = :expiresAt,
			       updated_at = :now,
			       updated_by = :actor
			 WHERE endpoint = :endpoint
			   AND key = :key
			   AND expires_at IS NOT NULL
			   AND expires_at <= :now
			""", nativeQuery = true)
	int reclaimExpired(@Param("key") String key,
			@Param("endpoint") String endpoint,
			@Param("requestHash") String requestHash,
			@Param("status") String status,
			@Param("expiresAt") OffsetDateTime expiresAt,
			@Param("now") OffsetDateTime now,
			@Param("actor") UUID actor);

	Optional<IdempotencyKey> findByEndpointAndKey(String endpoint, String key);
}