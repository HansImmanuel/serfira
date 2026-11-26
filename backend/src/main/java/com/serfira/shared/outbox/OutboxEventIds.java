package com.serfira.shared.outbox;

import com.serfira.shared.clock.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

/**
 * Issues outbox event ids: UUIDv7 (RFC 9562), strictly increasing within this process (ADR-027 D4).
 *
 * <p>The publisher claims rows {@code ORDER BY created_at, id}. {@code created_at} is stamped per persist from
 * the application clock, so two events of one transaction usually differ in it already; when they tie (always
 * under a fixed test clock, possible at the clock's resolution) a random id would order them by chance. A
 * monotonic id breaks the tie as written, so a {@code SettlementExecuted} precedes the {@code ContractClosed} it
 * caused as long as the wall clock does not step backwards between the two writes. PostgreSQL compares
 * {@code uuid} byte-wise, which is this layout's order.
 *
 * <p>Layout: 48-bit Unix milliseconds | version {@code 0111} | 12-bit counter | variant {@code 10} | 62 random
 * bits. The millisecond never goes backwards (a clock moved back reuses the last one), a repeat within the
 * same millisecond increments the counter, and a counter overflow borrows the next millisecond.
 */
@Component
public class OutboxEventIds {

	private static final int MAX_COUNTER = 0xFFF;

	private final Clock clock;
	private long lastMillis = -1L;
	private int counter;

	public OutboxEventIds(Clock clock) {
		this.clock = Objects.requireNonNull(clock, "clock");
	}

	/** The next id; strictly greater (byte-wise) than every id this instance issued before. */
	public synchronized UUID next() {
		long millis = Math.max(clock.instant().toEpochMilli(), lastMillis);
		if (millis == lastMillis) {
			counter++;
			if (counter > MAX_COUNTER) {
				millis++;
				counter = 0;
			}
		} else {
			counter = 0;
		}
		lastMillis = millis;

		long mostSignificant = (millis << 16) | (0x7L << 12) | counter;
		long leastSignificant = (ThreadLocalRandom.current().nextLong() & 0x3FFF_FFFF_FFFF_FFFFL)
				| 0x8000_0000_0000_0000L;
		return new UUID(mostSignificant, leastSignificant);
	}
}
