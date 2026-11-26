package com.serfira.payment.application.event;

import com.serfira.payment.domain.Payment;
import com.serfira.shared.outbox.OutboxPayload;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * {@code PaymentVoided} (ADR-027): a POSTED payment was reversed. {@code contractReopened} is {@code true} when
 * the void undid a MATURITY close (ADR-020 D1.3). The void reason is free text and is deliberately absent.
 */
public record PaymentVoidedPayload(int schemaVersion, UUID paymentId, String paymentNo, UUID contractId,
		BigDecimal amount, OffsetDateTime voidedAt, boolean contractReopened) implements OutboxPayload {

	public static PaymentVoidedPayload of(Payment payment, boolean contractReopened) {
		return new PaymentVoidedPayload(SCHEMA_VERSION, payment.getId(), payment.getPaymentNo(),
				payment.getContractId(), payment.getAmount(), payment.getVoidedAt(), contractReopened);
	}
}
