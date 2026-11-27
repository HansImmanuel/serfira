package com.serfira.payment.api;

import com.serfira.payment.domain.Payment;
import com.serfira.payment.domain.PaymentAllocation;
import com.serfira.payment.domain.PaymentChannel;
import com.serfira.payment.domain.PaymentStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * One row of a contract's payment history ({@code GET /contracts/{id}/payments}, task T39 Decision 4).
 *
 * <p>A separate record from {@link PaymentResponse}, which is also what an idempotent retry replays: widening
 * that one would change stored replays. This one adds the void fields, which stay {@code null} until the
 * payment is voided, and omits {@code contract_id} because the path already names it.
 *
 * @param excessAmount the EXCESS allocation as a single figure ({@code 0.00} when fully applied)
 * @param allocations  the stored allocation rows, kept after a void as history (DM §1.8)
 */
public record ContractPaymentResponse(
		UUID id,
		String paymentNo,
		BigDecimal amount,
		PaymentChannel channel,
		OffsetDateTime paidAt,
		PaymentStatus status,
		BigDecimal excessAmount,
		OffsetDateTime voidedAt,
		String voidReason,
		List<PaymentAllocationResponse> allocations) {

	public static ContractPaymentResponse from(Payment payment, List<PaymentAllocation> allocations) {
		return new ContractPaymentResponse(
				payment.getId(),
				payment.getPaymentNo(),
				payment.getAmount(),
				payment.getChannel(),
				payment.getPaidAt(),
				payment.getStatus(),
				PaymentResponse.excessOf(allocations),
				payment.getVoidedAt(),
				payment.getVoidReason(),
				allocations.stream().map(PaymentAllocationResponse::from).toList());
	}
}
