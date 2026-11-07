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
 * <p>A key is single-use forever per endpoint (ADR-017, option A): the row is never taken over on
 * expiry. {@code expires_at} only bounds how long the stored response is replayed; once it has
 * passed, reusing the key is rejected (see {@code IdempotencyService}). Physical cleanup of expired
 * rows is the separate hygiene job (story F4/T18): {@link #deleteExpiredBatch} deletes rows whose
 * {@code expires_at} has passed in bounded keyset pages.
 *
 * <p>Cleanup is deliberately <strong>scoped</strong> to the three create endpoints that have a
 * permanent business-row backstop: {@code POST /api/v1/contracts} ({@code uq_contract_idempotency}),
 * {@code POST /api/v1/payments} (the V18 all-status {@code uq_payment_idempotency}), and
 * {@code POST /api/v1/settlements} ({@code uq_settlement_idempotency}). For those, deleting a spent
 * claim row never re-opens the key — the backstop keeps it spent independently of the claim row
 * (ADR-017 2026-10-11 resolution, ADR-020 D3), so the cleanup adds no permanent-key probe. Payment-void
 * ({@code PaymentVoidApplicationService.VOID_ENDPOINT}, {@code POST /api/v1/payments/{id}/void}) gained its
 * own durable backstop in V22 ({@code uq_payment_void_idempotency}, T31), so it is now cleaned up too:
 * every successfully claimed void key — including a fresh key used on an already-VOIDED payment — writes a
 * durable association row, so deleting the expired claim leaves that row as the single-use-forever authority
 * (ADR-020 D3, PR #16 findings 1-2). Write-off
 * ({@code ContractWriteOffCommandService.WRITE_OFF_ENDPOINT}, {@code POST /api/v1/contracts/{id}/write-off})
 * still has <strong>no</strong> durable backstop, so its expired claim rows are intentionally retained:
 * deleting them would let the same key be reused against a different contract, violating
 * ADR-017 single-use-forever and ADR-020 D3. The scoping lives in the {@link #deleteExpiredBatch}
 * endpoint allow-list.
 */
public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, UUID> {

	@Modifying
	@Query(value = """
			INSERT INTO idempotency_keys
				(id, key, endpoint, request_hash, status, expires_at, request_id,
					created_at, created_by, updated_at, updated_by)
			VALUES (:id, :key, :endpoint, :requestHash, :status, :expiresAt, :requestId,
					:now, :actor, :now, :actor)
			ON CONFLICT (endpoint, key) DO NOTHING
			""", nativeQuery = true)
	int claim(@Param("id") UUID id,
			@Param("key") String key,
			@Param("endpoint") String endpoint,
			@Param("requestHash") String requestHash,
			@Param("status") String status,
			@Param("expiresAt") OffsetDateTime expiresAt,
			@Param("requestId") String requestId,
			@Param("now") OffsetDateTime now,
			@Param("actor") UUID actor);

	Optional<IdempotencyKey> findByEndpointAndKey(String endpoint, String key);

	/**
	 * Deletes up to {@code limit} rows whose retention window has already passed (F4/T18, TS §2.5),
	 * returning the number actually deleted. PostgreSQL {@code DELETE} has no {@code LIMIT}, so the
	 * bounded page is selected by a keyset-ordered subquery ({@code expires_at, id}) backed by the
	 * V21 {@code idx_idempotency_keys_expires_at} index; the cleanup job sums the per-page counts and
	 * stops when a page deletes fewer than {@code limit} rows. Live rows ({@code expires_at >= :now})
	 * are never touched.
	 *
	 * <p>The subselect is restricted to the endpoints with a durable business-row backstop: the three
	 * create endpoints ({@code POST /api/v1/contracts}, {@code POST /api/v1/payments},
	 * {@code POST /api/v1/settlements}) and, since V22/T31, payment-void
	 * ({@code POST /api/v1/payments/{id}/void}, backed by {@code uq_payment_void_idempotency}). For all four,
	 * deleting an expired claim leaves a durable row that keeps the key spent, so a reused key can never mint
	 * a second mutation against a different target (ADR-017 single-use-forever, ADR-020 D3, PR #16 finding 2).
	 * Write-off has no such backstop and is therefore retained even once expired. The literals are repeated
	 * inline rather than referencing the owning modules' endpoint constants, which are package-private and in
	 * {@code contract}/{@code payment}/{@code settlement} — {@code shared} must not depend on a business
	 * module. Each literal matches its constant exactly.
	 */
	@Modifying
	@Query(value = """
			DELETE FROM idempotency_keys
			WHERE id IN (
				SELECT id FROM idempotency_keys
				WHERE expires_at < :now
					AND endpoint IN ('POST /api/v1/contracts', 'POST /api/v1/payments', 'POST /api/v1/settlements',
						'POST /api/v1/payments/{id}/void')
				ORDER BY expires_at, id
				LIMIT :limit
			)
			""", nativeQuery = true)
	int deleteExpiredBatch(@Param("now") OffsetDateTime now, @Param("limit") int limit);
}