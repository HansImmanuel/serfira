package com.serfira.shared.outbox;

import com.serfira.shared.clock.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishes one batch of outbox events in its own transaction (ADR-027).
 *
 * <p>The claim is {@code FOR UPDATE SKIP LOCKED}, ordered by {@code (created_at, id)}: two concurrent batches
 * take disjoint rows, and the locks last only as long as this short transaction. A separate {@code @Service}
 * (not a method on {@link OutboxPublisherJob}) so {@code REQUIRES_NEW} is a real proxy call, as with
 * {@code IdempotencyKeyBatchDeleter}.
 *
 * <p>A sink failure is per event: the row counts a retry (or becomes {@code FAILED} at the ceiling) and the rest
 * of the batch still commits as {@code SENT}. A failure of this transaction itself rolls the whole batch back,
 * so the events it had already logged are logged again next time — the at-least-once case consumers
 * deduplicate by event id.
 */
@Service
public class OutboxBatchPublisher {

	private static final Logger LOGGER = LoggerFactory.getLogger(OutboxBatchPublisher.class);

	private final OutboxEventRepository repository;
	private final OutboxSink sink;
	private final Clock clock;
	private final int maxRetries;

	public OutboxBatchPublisher(OutboxEventRepository repository, OutboxSink sink, Clock clock,
			@Value("${serfira.jobs.outbox-publisher.max-retries:10}") int maxRetries) {
		if (maxRetries < 1) {
			throw new IllegalArgumentException("max-retries must be positive");
		}
		this.repository = repository;
		this.sink = sink;
		this.clock = clock;
		this.maxRetries = maxRetries;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public OutboxBatchResult publishBatch(int limit) {
		List<OutboxEvent> claimed = repository.claimPending(limit);
		int sent = 0;
		int failed = 0;
		for (OutboxEvent event : claimed) {
			try {
				sink.publish(event);
				event.markSent(clock.now());
				sent++;
			} catch (RuntimeException failure) {
				failed++;
				boolean terminal = event.recordFailure(maxRetries);
				// Id and type only: the payload is PII-free, but a sink's exception text is not ours to trust.
				LOGGER.warn("Outbox event {} ({}) was not published (attempt {}, {}){}", event.getId(),
						event.getEventType(), event.getRetryCount(), failure.getClass().getSimpleName(),
						terminal ? "; marked FAILED" : "");
			}
		}
		return new OutboxBatchResult(claimed.size(), sent, failed);
	}
}
