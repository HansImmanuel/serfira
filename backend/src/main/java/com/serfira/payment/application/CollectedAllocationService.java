package com.serfira.payment.application;

import com.serfira.payment.domain.PaymentStatus;
import com.serfira.payment.infrastructure.PaymentAllocationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Implementation of {@link CollectedAllocationPort} (T36): sums the POSTED, non-EXCESS allocations of the
 * cohort in chunks, so a large cohort never exceeds PostgreSQL's bind-parameter limit on the {@code in} list.
 */
@Service
public class CollectedAllocationService implements CollectedAllocationPort {

	/** Ids per query; well under PostgreSQL's 32,767 bind parameters. */
	static final int CHUNK_SIZE = 1_000;

	private static final int MONEY_SCALE = 2;

	private final PaymentAllocationRepository allocations;

	public CollectedAllocationService(PaymentAllocationRepository allocations) {
		this.allocations = allocations;
	}

	@Override
	@Transactional(readOnly = true)
	public BigDecimal sumCollected(Collection<UUID> installmentIds) {
		Objects.requireNonNull(installmentIds, "installmentIds");
		List<UUID> ids = List.copyOf(installmentIds);
		BigDecimal total = BigDecimal.ZERO;
		for (int from = 0; from < ids.size(); from += CHUNK_SIZE) {
			List<UUID> chunk = new ArrayList<>(ids.subList(from, Math.min(from + CHUNK_SIZE, ids.size())));
			total = total.add(allocations.sumResolvedAmountByInstallmentIds(PaymentStatus.POSTED, chunk));
		}
		return total.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
	}
}
