package com.serfira.payment.application;

import com.serfira.payment.domain.PaymentStatus;
import com.serfira.payment.infrastructure.PaymentAllocationRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T36 — the collected read sums POSTED allocations only and splits a large cohort into chunks, so the
 * {@code in} list never exceeds PostgreSQL's bind-parameter limit. Ids are deduplicated before chunking, so a
 * repeated id is never counted in two chunks (PR #28 review). The exclusions themselves (EXCESS, VOIDED,
 * credit applications) are pinned against PostgreSQL by {@code OutstandingReportIT}.
 */
class CollectedAllocationServiceTest {

	private final PaymentAllocationRepository allocations = mock(PaymentAllocationRepository.class);
	private final CollectedAllocationService service = new CollectedAllocationService(allocations);

	@Test
	void anEmptyCohortCollectsZeroWithoutAQuery() {
		assertThat(service.sumCollected(List.of())).isEqualTo(new BigDecimal("0.00"));
		verify(allocations, never()).sumResolvedAmountByInstallmentIds(any(), anyCollection());
	}

	@Test
	void sumsPostedAllocationsChunkByChunk() {
		List<UUID> cohort = IntStream.range(0, CollectedAllocationService.CHUNK_SIZE * 2 + 5)
				.mapToObj(i -> UUID.randomUUID())
				.toList();
		List<Integer> chunkSizes = new ArrayList<>();
		when(allocations.sumResolvedAmountByInstallmentIds(eq(PaymentStatus.POSTED), anyCollection()))
				.thenAnswer(invocation -> {
					Collection<?> chunk = invocation.getArgument(1);
					chunkSizes.add(chunk.size());
					return new BigDecimal("100.00");
				});

		BigDecimal collected = service.sumCollected(cohort);

		assertThat(chunkSizes).containsExactly(CollectedAllocationService.CHUNK_SIZE,
				CollectedAllocationService.CHUNK_SIZE, 5);
		assertThat(collected).isEqualTo(new BigDecimal("300.00"));
	}

	@Test
	void aRepeatedIdWithinAChunkIsQueriedOnceInFirstOccurrenceOrder() {
		UUID a = UUID.randomUUID();
		UUID b = UUID.randomUUID();
		UUID c = UUID.randomUUID();
		List<List<UUID>> chunks = recordChunksAtOneHundredPerDistinctId();

		BigDecimal collected = service.sumCollected(List.of(a, b, a, c, b));

		assertThat(chunks).containsExactly(List.of(a, b, c));
		assertThat(collected).isEqualTo(new BigDecimal("300.00"));
	}

	@Test
	void aRepeatedIdAcrossTheChunkBoundaryCountsOnce() {
		List<UUID> distinct = IntStream.range(0, CollectedAllocationService.CHUNK_SIZE)
				.mapToObj(i -> UUID.randomUUID())
				.toList();
		UUID last = UUID.randomUUID();
		List<UUID> cohort = new ArrayList<>(distinct);
		cohort.add(distinct.getFirst()); // would land in the second chunk without deduplication
		cohort.add(last);
		List<List<UUID>> chunks = recordChunksAtOneHundredPerDistinctId();

		BigDecimal collected = service.sumCollected(cohort);

		assertThat(chunks).hasSize(2);
		assertThat(chunks.get(0)).isEqualTo(distinct);
		assertThat(chunks.get(1)).containsExactly(last);
		assertThat(collected).isEqualTo(new BigDecimal("100100.00"));
	}

	@Test
	void oneIdRepeatedPastTheChunkSizeIsQueriedOnce() {
		UUID id = UUID.randomUUID();
		List<List<UUID>> chunks = recordChunksAtOneHundredPerDistinctId();

		BigDecimal collected = service.sumCollected(Collections.nCopies(CollectedAllocationService.CHUNK_SIZE + 1, id));

		assertThat(chunks).containsExactly(List.of(id));
		assertThat(collected).isEqualTo(new BigDecimal("100.00"));
	}

	/**
	 * Stubs the repository like PostgreSQL's {@code in} list: each distinct id in one query contributes its
	 * allocations once (100.00 here). Returns the chunks queried, in order.
	 */
	private List<List<UUID>> recordChunksAtOneHundredPerDistinctId() {
		List<List<UUID>> chunks = new ArrayList<>();
		when(allocations.sumResolvedAmountByInstallmentIds(eq(PaymentStatus.POSTED), anyCollection()))
				.thenAnswer(invocation -> {
					Collection<UUID> chunk = invocation.getArgument(1);
					chunks.add(List.copyOf(chunk));
					return new BigDecimal("100.00").multiply(BigDecimal.valueOf(new HashSet<>(chunk).size()));
				});
		return chunks;
	}
}
