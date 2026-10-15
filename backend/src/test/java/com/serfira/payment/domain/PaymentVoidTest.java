package com.serfira.payment.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * E4 — {@link Payment#markVoided(OffsetDateTime, String)}, the status flip of a payment void (task T16,
 * ADR-020 D1): a reversal, never a deletion. The amount and allocations are untouched; only
 * status/voidedAt/voidReason change.
 */
class PaymentVoidTest {

	private static final OffsetDateTime PAID_AT =
			OffsetDateTime.of(2026, 9, 21, 9, 30, 0, 0, ZoneOffset.ofHours(7));
	private static final OffsetDateTime VOIDED_AT =
			OffsetDateTime.of(2026, 9, 25, 14, 0, 0, 0, ZoneOffset.ofHours(7));

	@Test
	void voidingAPostedPaymentFlipsItToVoidedAndStampsTheReasonAndInstant() {
		Payment payment = posted();

		boolean changed = payment.markVoided(VOIDED_AT, "customer disputed the receipt");

		assertThat(changed).isTrue();
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.VOIDED);
		assertThat(payment.getVoidedAt()).isEqualTo(VOIDED_AT);
		assertThat(payment.getVoidReason()).isEqualTo("customer disputed the receipt");
		// The amount is left intact — a void is a reversal, not a rewrite of the money event.
		assertThat(payment.getAmount()).isEqualByComparingTo("1000000.00");
	}

	@Test
	void voidingWithABlankReasonStoresTheDefaultReasonSoTheCheckConstraintHolds() {
		Payment payment = posted();

		payment.markVoided(VOIDED_AT, "   ");

		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.VOIDED);
		// ck_payment_voided_coherence (V4) forbids a null void_reason on a VOIDED row, so a blank/missing
		// reason is stored as the default rather than null (ADR-020: the void body is optional).
		assertThat(payment.getVoidReason()).isEqualTo("(no reason given)");
	}

	@Test
	void voidingWithANullReasonStoresTheDefaultReason() {
		Payment payment = posted();

		payment.markVoided(VOIDED_AT, null);

		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.VOIDED);
		assertThat(payment.getVoidReason()).isEqualTo("(no reason given)");
	}

	@Test
	void voidingAnAlreadyVoidedPaymentIsANoOp() {
		Payment payment = posted();
		payment.markVoided(VOIDED_AT, "first void");

		boolean changed = payment.markVoided(VOIDED_AT.plusDays(1), "second void");

		// Idempotent by state: no second transition, and the original void details are kept.
		assertThat(changed).isFalse();
		assertThat(payment.getVoidedAt()).isEqualTo(VOIDED_AT);
		assertThat(payment.getVoidReason()).isEqualTo("first void");
	}

	@Test
	void aNullVoidInstantIsRejected() {
		Payment payment = posted();

		assertThatThrownBy(() -> payment.markVoided(null, "reason"))
				.isInstanceOf(NullPointerException.class);
	}

	private static Payment posted() {
		return new Payment("PAY-202609-0001", UUID.randomUUID(), new BigDecimal("1000000.00"),
				PaymentChannel.CASH, PAID_AT, "idem-key");
	}
}
