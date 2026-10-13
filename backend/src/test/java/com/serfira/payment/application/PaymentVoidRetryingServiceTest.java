package com.serfira.payment.application;

import com.serfira.payment.api.PaymentResponse;
import com.serfira.payment.api.VoidPaymentRequest;
import com.serfira.shared.concurrency.Sleeper;
import com.serfira.shared.error.PaymentNotVoidableException;
import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The retry policy of the payment void (task T16, ADR-020 D6): attempt count, backoff sequence and the
 * retryable/non-retryable split, using an injected {@link Sleeper} so the suite runs instantly. Only an
 * optimistic-lock conflict is retried — a void posts no unique business row to collide on.
 */
class PaymentVoidRetryingServiceTest {

	private static final UUID PAYMENT_ID = UUID.randomUUID();
	private static final String IDEMPOTENCY_KEY = "void-key-1";
	private static final VoidPaymentRequest REQUEST = new VoidPaymentRequest("reason");

	private final PaymentVoidApplicationService voids = mock(PaymentVoidApplicationService.class);
	private final RecordingSleeper sleeper = new RecordingSleeper();
	private final PaymentVoidRetryingService retrying = new PaymentVoidRetryingService(voids, sleeper);

	@Test
	void succeedsOnFirstAttemptWithoutSleeping() {
		PaymentResponse expected = mock(PaymentResponse.class);
		when(voids.voidPayment(PAYMENT_ID, IDEMPOTENCY_KEY, REQUEST)).thenReturn(expected);

		PaymentResponse actual = retrying.voidPayment(PAYMENT_ID, IDEMPOTENCY_KEY, REQUEST);

		assertThat(actual).isSameAs(expected);
		verify(voids, times(1)).voidPayment(PAYMENT_ID, IDEMPOTENCY_KEY, REQUEST);
		assertThat(sleeper.recordedMillis).isEmpty();
	}

	@Test
	void retriesOptimisticLockFailuresWithTheDocumentedBackoffThenSucceeds() {
		PaymentResponse expected = mock(PaymentResponse.class);
		when(voids.voidPayment(PAYMENT_ID, IDEMPOTENCY_KEY, REQUEST))
				.thenThrow(new ObjectOptimisticLockingFailureException(Object.class, "installment-1"))
				.thenThrow(new OptimisticLockException("second attempt also lost the race"))
				.thenReturn(expected);

		PaymentResponse actual = retrying.voidPayment(PAYMENT_ID, IDEMPOTENCY_KEY, REQUEST);

		assertThat(actual).isSameAs(expected);
		verify(voids, times(3)).voidPayment(PAYMENT_ID, IDEMPOTENCY_KEY, REQUEST);
		assertThat(sleeper.recordedMillis).containsExactly(50L, 150L);
	}

	@Test
	void exhaustsAtThreeAttemptsThenThrowsConcurrentModification() {
		when(voids.voidPayment(PAYMENT_ID, IDEMPOTENCY_KEY, REQUEST))
				.thenThrow(new ObjectOptimisticLockingFailureException(Object.class, "installment-1"));

		assertThatThrownBy(() -> retrying.voidPayment(PAYMENT_ID, IDEMPOTENCY_KEY, REQUEST))
				.isInstanceOf(PaymentConflictRetriesExhaustedException.class)
				.hasCauseInstanceOf(ObjectOptimisticLockingFailureException.class);

		verify(voids, times(3)).voidPayment(PAYMENT_ID, IDEMPOTENCY_KEY, REQUEST);
		assertThat(sleeper.recordedMillis).containsExactly(50L, 150L);
	}

	@Test
	void doesNotRetryBusinessStateErrors() {
		PaymentNotVoidableException notVoidable = new PaymentNotVoidableException("already settled");
		when(voids.voidPayment(PAYMENT_ID, IDEMPOTENCY_KEY, REQUEST)).thenThrow(notVoidable);

		assertThatThrownBy(() -> retrying.voidPayment(PAYMENT_ID, IDEMPOTENCY_KEY, REQUEST))
				.isSameAs(notVoidable);

		verify(voids, times(1)).voidPayment(PAYMENT_ID, IDEMPOTENCY_KEY, REQUEST);
		assertThat(sleeper.recordedMillis).isEmpty();
	}

	/** Fake {@link Sleeper} that records the requested durations instead of blocking. */
	private static final class RecordingSleeper implements Sleeper {
		private final List<Long> recordedMillis = new ArrayList<>();

		@Override
		public void sleep(long millis) {
			recordedMillis.add(millis);
		}
	}
}
