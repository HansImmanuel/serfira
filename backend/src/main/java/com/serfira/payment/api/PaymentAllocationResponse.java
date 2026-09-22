package com.serfira.payment.api;

import com.serfira.payment.domain.AllocationType;
import com.serfira.payment.domain.allocation.AllocationLine;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One allocation row of a posted payment (PRD P-2/P-3/P-4, DM §1.8).
 *
 * <p>{@code installment_id} is {@code null} exactly for {@link AllocationType#EXCESS}: the amount could
 * not be applied to any due installment and is held as customer credit ({@code TITIPAN_NASABAH}), never
 * applied automatically to a future installment (PRD P-4, Addendum §2.1, §16.5).
 *
 * @param installmentId  installment the amount resolved; {@code null} for EXCESS
 * @param allocationType component the amount resolved, in the order the engine applied it; the wire name
 *                       is {@code allocation_type}, the same name the column carries (DM §1.8)
 * @param amount         scale-2 money, always {@code > 0}
 */
public record PaymentAllocationResponse(UUID installmentId, AllocationType allocationType, BigDecimal amount) {

	public static PaymentAllocationResponse from(AllocationLine line) {
		return new PaymentAllocationResponse(line.installmentRef(), line.type(), line.amount());
	}
}
