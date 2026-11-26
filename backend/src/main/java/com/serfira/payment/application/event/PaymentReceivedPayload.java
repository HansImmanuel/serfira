package com.serfira.payment.application.event;

import com.serfira.payment.domain.Payment;
import com.serfira.payment.domain.PaymentChannel;
import com.serfira.shared.outbox.OutboxPayload;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** {@code PaymentReceived} (ADR-027): a payment and its allocations were persisted. No PII, no free text. */
public record PaymentReceivedPayload(int schemaVersion, UUID paymentId, String paymentNo, UUID contractId,
		String contractNo, BigDecimal amount, PaymentChannel channel, OffsetDateTime paidAt,
		BigDecimal excessAmount) implements OutboxPayload {

	public static PaymentReceivedPayload of(Payment payment, String contractNo, BigDecimal excessAmount) {
		return new PaymentReceivedPayload(SCHEMA_VERSION, payment.getId(), payment.getPaymentNo(),
				payment.getContractId(), contractNo, payment.getAmount(), payment.getChannel(), payment.getPaidAt(),
				excessAmount);
	}
}
