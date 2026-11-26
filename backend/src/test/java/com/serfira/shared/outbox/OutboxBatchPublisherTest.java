package com.serfira.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.serfira.shared.clock.FixedClock;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** T38 / ADR-027: one claimed batch — sent rows become SENT, a sink failure retries, the ceiling is FAILED. */
class OutboxBatchPublisherTest {

	private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
	private final FixedClock clock = new FixedClock(LocalDate.of(2026, 11, 26));
	private final FailingSink sink = new FailingSink();

	/** A sink that refuses the events whose ids it was told to refuse. */
	static final class FailingSink implements OutboxSink {
		Set<UUID> refuse = Set.of();

		@Override
		public void publish(OutboxEvent event) {
			if (refuse.contains(event.getId())) {
				throw new IllegalStateException("broker unavailable");
			}
		}
	}

	@Test
	void aPublishedEventIsMarkedSentWithTheClockInstant() {
		OutboxEvent event = event();
		when(repository.claimPending(10)).thenReturn(List.of(event));

		OutboxBatchResult result = publisher(10).publishBatch(10);

		assertThat(result).isEqualTo(new OutboxBatchResult(1, 1, 0));
		assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.SENT);
		assertThat(event.getSentAt()).isEqualTo(clock.now());
	}

	@Test
	void aSinkFailureIncrementsRetryAndLeavesThePendingRow() {
		OutboxEvent event = event();
		sink.refuse = Set.of(event.getId());
		when(repository.claimPending(10)).thenReturn(List.of(event));

		OutboxBatchResult result = publisher(10).publishBatch(10);

		assertThat(result).isEqualTo(new OutboxBatchResult(1, 0, 1));
		assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
		assertThat(event.getRetryCount()).isEqualTo(1);
		assertThat(event.getSentAt()).isNull();
	}

	@Test
	void theLastAllowedFailureMarksTheRowFailed() {
		OutboxEvent event = event();
		sink.refuse = Set.of(event.getId());
		when(repository.claimPending(10)).thenReturn(List.of(event));
		OutboxBatchPublisher publisher = publisher(3);

		publisher.publishBatch(10);
		publisher.publishBatch(10);
		assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
		publisher.publishBatch(10);

		assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.FAILED);
		assertThat(event.getRetryCount()).isEqualTo(3);
	}

	@Test
	void aFailingEventDoesNotUndoTheRestOfTheBatch() {
		OutboxEvent first = event();
		OutboxEvent second = event();
		OutboxEvent third = event();
		sink.refuse = Set.of(second.getId());
		when(repository.claimPending(10)).thenReturn(List.of(first, second, third));

		OutboxBatchResult result = publisher(10).publishBatch(10);

		assertThat(result).isEqualTo(new OutboxBatchResult(3, 2, 1));
		assertThat(first.getStatus()).isEqualTo(OutboxEventStatus.SENT);
		assertThat(second.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
		assertThat(third.getStatus()).isEqualTo(OutboxEventStatus.SENT);
	}

	@Test
	void nonPositiveMaxRetriesIsRejected() {
		assertThatThrownBy(() -> publisher(0)).isInstanceOf(IllegalArgumentException.class);
	}

	private OutboxBatchPublisher publisher(int maxRetries) {
		return new OutboxBatchPublisher(repository, sink, clock, maxRetries);
	}

	private static OutboxEvent event() {
		return new OutboxEvent(UUID.randomUUID(), OutboxAggregateType.PAYMENT, UUID.randomUUID(),
				OutboxEventType.PaymentReceived, "{\"schema_version\":1}");
	}
}
