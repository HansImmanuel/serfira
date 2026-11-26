package com.serfira.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.serfira.shared.clock.FixedClock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * T38 / ADR-027: outbox event ids are UUIDv7 and strictly increasing within the process, so two events
 * written in one transaction with tied {@code created_at} values still sort in write order under
 * {@code ORDER BY created_at, id}. PostgreSQL compares {@code uuid} byte-wise, which is the same order as the
 * lowercase hex text compared lexically, so the text comparison below is the database's order.
 */
class OutboxEventIdsTest {

	private final FixedClock clock = new FixedClock(LocalDate.of(2026, 11, 26));
	private final OutboxEventIds ids = new OutboxEventIds(clock);

	@Test
	void idsAreVersion7WithTheRfcVariant() {
		UUID id = ids.next();

		assertThat(id.version()).isEqualTo(7);
		assertThat(id.variant()).isEqualTo(2);
	}

	@Test
	void idsUnderAFrozenClockStrictlyIncrease() {
		String previous = ids.next().toString();
		for (int i = 0; i < 10_000; i++) {
			String next = ids.next().toString();
			assertThat(next).isGreaterThan(previous);
			previous = next;
		}
	}

	@Test
	void idsKeepIncreasingWhenTheClockMovesBackwards() {
		String before = ids.next().toString();
		clock.set(clock.now().minus(Duration.ofHours(1)));

		String after = ids.next().toString();

		assertThat(after).isGreaterThan(before);
	}

	@Test
	void theTimestampIsTheClockMillis() {
		UUID id = ids.next();

		assertThat(id.getMostSignificantBits() >>> 16).isEqualTo(clock.instant().toEpochMilli());
	}
}
