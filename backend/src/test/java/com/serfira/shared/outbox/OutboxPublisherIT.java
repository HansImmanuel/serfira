package com.serfira.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.serfira.TestcontainersConfiguration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * T38 / ADR-027: the publisher against PostgreSQL — ordered drain to {@code SENT}, {@code SENT} rows left alone,
 * {@code SKIP LOCKED} keeping two concurrent batches disjoint, and a row that always fails ending {@code FAILED}
 * after the configured retries, one failing tick (and one {@code job_run}) at a time.
 */
@Import({TestcontainersConfiguration.class, OutboxPublisherIT.ControllableSinkConfig.class})
@SpringBootTest
@TestPropertySource(properties = "serfira.jobs.outbox-publisher.max-retries=3")
class OutboxPublisherIT {

	record ProbePayload(int schemaVersion, int sequence) implements OutboxPayload {
	}

	/** Records what it published; can refuse chosen ids, and can hold one id until released. */
	static final class ControllableSink implements OutboxSink {
		final List<UUID> published = Collections.synchronizedList(new ArrayList<>());
		final Set<UUID> refuse = ConcurrentHashMap.newKeySet();
		volatile UUID holdId;
		volatile CountDownLatch holding = new CountDownLatch(1);
		volatile CountDownLatch release = new CountDownLatch(0);

		@Override
		public void publish(OutboxEvent event) {
			if (event.getId().equals(holdId)) {
				holding.countDown();
				await(release);
			}
			if (refuse.contains(event.getId())) {
				throw new IllegalStateException("broker unavailable");
			}
			published.add(event.getId());
		}

		void reset() {
			published.clear();
			refuse.clear();
			holdId = null;
			holding = new CountDownLatch(1);
			release = new CountDownLatch(0);
		}

		private static void await(CountDownLatch latch) {
			try {
				assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(ex);
			}
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class ControllableSinkConfig {
		@Bean
		@Primary
		ControllableSink controllableSink() {
			return new ControllableSink();
		}
	}

	@Autowired
	OutboxWriter writer;

	@Autowired
	OutboxBatchPublisher batches;

	@Autowired
	LockedOutboxPublisherJob job;

	@Autowired
	ControllableSink sink;

	@Autowired
	TransactionTemplate transactions;

	@Autowired
	JdbcTemplate jdbc;

	@BeforeEach
	void cleanTables() {
		jdbc.execute("TRUNCATE TABLE outbox_events, job_run");
		sink.reset();
	}

	@AfterEach
	void leaveCleanSharedDatabase() {
		jdbc.execute("TRUNCATE TABLE outbox_events, job_run");
		sink.reset();
	}

	@Test
	void aTickDrainsEveryPendingRowInOrderAndMarksThemSent() {
		List<UUID> written = seed(5);

		job.run();

		assertThat(sink.published).containsExactlyElementsOf(written);
		assertThat(jdbc.queryForObject(
				"select count(*) from outbox_events where status = 'SENT' and sent_at is not null", Integer.class))
				.isEqualTo(5);
		job.run();
		assertThat(sink.published).hasSize(5);
		assertThat(jobRuns()).isZero();
	}

	@Test
	void sentRowsAreLeftAlone() {
		UUID sent = seed(1).get(0);
		job.run();
		sink.published.clear();

		UUID fresh = seed(1).get(0);
		job.run();

		assertThat(sink.published).containsExactly(fresh).doesNotContain(sent);
	}

	@Test
	void twoConcurrentBatchesNeverPublishTheSameRow() throws Exception {
		List<UUID> written = seed(4);
		sink.holdId = written.get(0);
		sink.release = new CountDownLatch(1);

		// A claims rows 1-2 and stalls inside its transaction on row 1, holding both row locks.
		CompletableFuture<OutboxBatchResult> first = CompletableFuture.supplyAsync(() -> batches.publishBatch(2));
		assertThat(sink.holding.await(30, TimeUnit.SECONDS)).isTrue();
		// B must skip A's locked rows and take 3-4 at once, without waiting for A.
		OutboxBatchResult second = CompletableFuture.supplyAsync(() -> batches.publishBatch(10))
				.get(30, TimeUnit.SECONDS);
		sink.release.countDown();
		OutboxBatchResult firstResult = first.get(30, TimeUnit.SECONDS);

		assertThat(second).isEqualTo(new OutboxBatchResult(2, 2, 0));
		assertThat(firstResult).isEqualTo(new OutboxBatchResult(2, 2, 0));
		assertThat(sink.published).hasSize(4).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(written);
	}

	@Test
	void aRowFailingEveryTickEndsFailedAfterMaxRetries() {
		List<UUID> written = seed(2);
		sink.refuse.add(written.get(0));

		job.run();
		job.run();
		assertThat(statusOf(written.get(0))).isEqualTo("PENDING");
		job.run();

		assertThat(statusOf(written.get(0))).isEqualTo("FAILED");
		assertThat(jdbc.queryForObject("select retry_count from outbox_events where id = ?", Integer.class,
				written.get(0))).isEqualTo(3);
		assertThat(statusOf(written.get(1))).isEqualTo("SENT");
		assertThat(jdbc.queryForObject(
				"select count(*) from job_run where job_name = 'outbox-publisher' and status = 'FAILED'",
				Integer.class)).isEqualTo(3);
		job.run();
		assertThat(jobRuns()).isEqualTo(3);
	}

	private List<UUID> seed(int count) {
		List<UUID> ids = new ArrayList<>();
		transactions.executeWithoutResult(status -> {
			for (int i = 0; i < count; i++) {
				ids.add(writer.append(OutboxAggregateType.PAYMENT, UUID.randomUUID(), OutboxEventType.PaymentReceived,
						new ProbePayload(OutboxPayload.SCHEMA_VERSION, i)));
			}
		});
		return ids;
	}

	private String statusOf(UUID id) {
		return jdbc.queryForObject("select status from outbox_events where id = ?", String.class, id);
	}

	private int jobRuns() {
		return jdbc.queryForObject("select count(*) from job_run where job_name = 'outbox-publisher'", Integer.class);
	}
}
