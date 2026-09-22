package com.serfira.payment.api;

import com.serfira.payment.domain.Payment;
import com.serfira.payment.domain.PaymentChannel;
import com.serfira.payment.domain.PaymentStatus;
import com.serfira.payment.domain.allocation.AllocationResult;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * A posted payment with the allocation it produced (PRD P-1/P-2/P-4, DM §1.7/§1.8).
 *
 * <p>The allocations are returned because the allocation <b>is</b> the outcome of receiving money: the
 * operator has to see which installments were settled by it. {@code excess_amount} repeats the EXCESS
 * line as a single figure, so a client does not have to scan the list to warn about customer credit.
 *
 * <p>This record is also what an identical retry replays: the response is stored as the idempotent
 * result payload (TS §2.5), so it carries no request-scoped data (no correlation id, no "replayed"
 * flag) and stays deserializable from its own JSON.
 *
 * @param paidAt         business instant taken from the server clock (TS §2.0)
 * @param excessAmount   amount held as customer credit; {@code 0.00} when the payment was fully applied
 * @param allocations    ordered allocation lines, EXCESS last — {@code Σ amount = payment amount} (invariant 6)
 */
public record PaymentResponse(
		UUID id,
		String paymentNo,
		UUID contractId,
		BigDecimal amount,
		PaymentChannel channel,
		OffsetDateTime paidAt,
		PaymentStatus status,
		BigDecimal excessAmount,
		List<PaymentAllocationResponse> allocations) {

	public static PaymentResponse from(Payment payment, AllocationResult allocation) {
		return new PaymentResponse(
				payment.getId(),
				payment.getPaymentNo(),
				payment.getContractId(),
				payment.getAmount(),
				payment.getChannel(),
				payment.getPaidAt(),
				payment.getStatus(),
				allocation.excessAmount(),
				allocation.lines().stream().map(PaymentAllocationResponse::from).toList());
	}
}
