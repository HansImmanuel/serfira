package com.serfira.shared.idempotency;

import com.serfira.shared.clock.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional boundary for one page of the idempotency-key cleanup (F4/T18).
 *
 * <p>A separate {@code @Service} (not a method on {@link IdempotencyCleanupJob}) so the
 * {@code REQUIRES_NEW} boundary is a real Spring proxy call, avoiding the self-invocation pitfall: each
 * page commits in its own short transaction and holds no long lock over the whole table, consistent with
 * the per-contract transaction isolation of the daily servicing job. The expiry cutoff comes from the
 * injected {@link Clock} (never {@code OffsetDateTime.now()}), anchored to Asia/Jakarta (TS §2.0).
 */
@Service
public class IdempotencyKeyBatchDeleter {

	private final IdempotencyKeyRepository repository;
	private final Clock clock;

	public IdempotencyKeyBatchDeleter(IdempotencyKeyRepository repository, Clock clock) {
		this.repository = repository;
		this.clock = clock;
	}

	/** Deletes up to {@code limit} expired rows in a fresh transaction, returning how many were deleted. */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public int deletePage(int limit) {
		return repository.deleteExpiredBatch(clock.now(), limit);
	}
}
