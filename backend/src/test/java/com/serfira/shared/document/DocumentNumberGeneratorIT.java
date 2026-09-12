package com.serfira.shared.document;

import com.serfira.TestcontainersConfiguration;
import com.serfira.shared.clock.Clock;
import com.serfira.shared.clock.FixedClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sprint 0 / A4 — verifies the document number generator against PostgreSQL 16: sequential
 * increments, period isolation, independent counters per type, and lock-based uniqueness under
 * real concurrency.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class DocumentNumberGeneratorIT {

	@Autowired
	DocumentNumberGenerator generator;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	Clock clock;

	@BeforeEach
	void resetCounters() {
		jdbc.update("delete from document_number_counter");
	}

	@Test
	void generatesSequentialNumbersWithinAPeriod() {
		String first = generator.next(DocumentType.CONTRACT);
		String second = generator.next(DocumentType.CONTRACT);
		String third = generator.next(DocumentType.CONTRACT);

		assertThat(first).matches("MF-\\d{6}-\\d{4}");
		assertThat(second).isEqualTo(incrementSuffix(first));
		assertThat(third).isEqualTo(incrementSuffix(second));
	}

	@Test
	void countersResetPerMonthAndPerType() {
		fixedClock().setDate(LocalDate.of(2026, 9, 12));
		String sepContract = generator.next(DocumentType.CONTRACT);
		assertThat(sepContract).isEqualTo("MF-202609-0001");

		fixedClock().setDate(LocalDate.of(2026, 10, 5));
		assertThat(generator.next(DocumentType.CONTRACT)).isEqualTo("MF-202610-0001");

		// back to September: the same monthly counter continues, the payment counter is independent
		fixedClock().setDate(LocalDate.of(2026, 9, 20));
		assertThat(generator.next(DocumentType.CONTRACT)).isEqualTo(incrementSuffix(sepContract));
		assertThat(generator.next(DocumentType.PAYMENT)).isEqualTo("PAY-202609-0001");
	}

	@Test
	void quoteNumbersAreScopedPerDay() {
		fixedClock().setDate(LocalDate.of(2026, 1, 31));
		assertThat(generator.next(DocumentType.QUOTE)).isEqualTo("Q-20260131-0001");

		fixedClock().setDate(LocalDate.of(2026, 2, 1));
		assertThat(generator.next(DocumentType.QUOTE)).isEqualTo("Q-20260201-0001");
	}

	@Test
	void allTypesUseTheirOwnCounters() {
		fixedClock().setDate(LocalDate.of(2026, 6, 15));
		assertThat(generator.next(DocumentType.CONTRACT)).startsWith("MF-202606");
		assertThat(generator.next(DocumentType.PAYMENT)).startsWith("PAY-202606");
		assertThat(generator.next(DocumentType.QUOTE)).startsWith("Q-20260615");
		assertThat(generator.next(DocumentType.SETTLEMENT)).startsWith("SET-202606");
	}

	@Test
	void concurrentGenerationProducesNoDuplicates() throws Exception {
		fixedClock().setDate(LocalDate.of(2026, 8, 8));
		int threads = 8;
		int perThread = 25;
		Set<String> numbers = new ConcurrentSkipListSet<>();
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);

		try {
			IntStream.range(0, threads).forEach(t -> pool.submit(() -> {
				try {
					start.await();
					for (int i = 0; i < perThread; i++) {
						numbers.add(generator.next(DocumentType.CONTRACT));
					}
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException(e);
				}
				return null;
			}));
			start.countDown();
		} finally {
			pool.shutdown();
			assertThat(pool.awaitTermination(120, TimeUnit.SECONDS)).isTrue();
		}

		assertThat(numbers).hasSize(threads * perThread);
		assertThat(numbers).allMatch(number -> number.startsWith("MF-202608"));
	}

	@Test
	void counterRowsArePersistedForAudit() {
		fixedClock().setDate(LocalDate.of(2026, 4, 1));
		generator.next(DocumentType.CONTRACT);
		generator.next(DocumentType.CONTRACT);

		Integer lastValue = jdbc.queryForObject(
				"select last_value from document_number_counter where counter_key = 'CONTRACT_202604'", Integer.class);
		assertThat(lastValue).isEqualTo(2);
	}

	private FixedClock fixedClock() {
		return (FixedClock) clock;
	}

	private static String incrementSuffix(String number) {
		int separator = number.lastIndexOf('-');
		int suffix = Integer.parseInt(number.substring(separator + 1));
		return number.substring(0, separator + 1) + String.format("%04d", suffix + 1);
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class FixedClockConfig {

		/**
		 * Named differently from {@code ClockConfiguration#clock} so both bean definitions can coexist;
		 * {@code @Primary} makes this the bean injected everywhere {@code Clock} is required.
		 */
		@Bean
		@Primary
		Clock fixedClock() {
			return new FixedClock(LocalDate.of(2026, 9, 12));
		}
	}
}