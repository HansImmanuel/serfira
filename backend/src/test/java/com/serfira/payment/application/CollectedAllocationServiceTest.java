package com.serfira.payment.application;

import com.serfira.payment.domain.PaymentStatus;
import com.serfira.payment.infrastructure.PaymentAllocationRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
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
 * {@code in} list never exceeds PostgreSQL's bind-parameter limit. The exclusions themselves (EXCESS, VOIDED,
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
}
