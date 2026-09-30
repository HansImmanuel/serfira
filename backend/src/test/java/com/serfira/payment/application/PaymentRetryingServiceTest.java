package com.serfira.payment.application;

import com.serfira.payment.api.PaymentRequest;
import com.serfira.payment.api.PaymentResponse;
import com.serfira.payment.domain.PaymentChannel;
import com.serfira.shared.concurrency.Sleeper;
import com.serfira.shared.error.BadRequestException;
import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.sql.SQLException;
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
 * The retry policy of `docs/tasks.md` T5 (Addendum §5): attempt count, backoff sequence and the
 * retryable/non-retryable split, using an injected {@link Sleeper} so the suite runs instantly.
 */
class PaymentRetryingServiceTest {

	private static final String IDEMPOTENCY_KEY = "key-1";
	private static final PaymentRequest REQUEST =
			new PaymentRequest(UUID.randomUUID(), new BigDecimal("100000.00"), PaymentChannel.BANK_TRANSFER);

	private final PaymentApplicationService payments = mock(PaymentApplicationService.class);
	private final RecordingSleeper sleeper = new RecordingSleeper();
	private final PaymentRetryingService retrying = new PaymentRetryingService(payments, sleeper);

	@Test
	void succeedsOnFirstAttemptWithoutSleeping() {
		PaymentResponse expected = mock(PaymentResponse.class);
		when(payments.create(IDEMPOTENCY_KEY, REQUEST)).thenReturn(expected);

		PaymentResponse actual = retrying.create(IDEMPOTENCY_KEY, REQUEST);

		assertThat(actual).isSameAs(expected);
		verify(payments, times(1)).create(IDEMPOTENCY_KEY, REQUEST);
		assertThat(sleeper.recordedMillis).isEmpty();
	}

	@Test
	void retriesOptimisticLockFailuresWithTheDocumentedBackoffThenSucceeds() {
		PaymentResponse expected = mock(PaymentResponse.class);
		when(payments.create(IDEMPOTENCY_KEY, REQUEST))
				.thenThrow(new ObjectOptimisticLockingFailureException(Object.class, "installment-1"))
				.thenThrow(new OptimisticLockException("second attempt also lost the race"))
				.thenReturn(expected);

		PaymentResponse actual = retrying.create(IDEMPOTENCY_KEY, REQUEST);

		assertThat(actual).isSameAs(expected);
		verify(payments, times(3)).create(IDEMPOTENCY_KEY, REQUEST);
		assertThat(sleeper.recordedMillis).containsExactly(50L, 150L);
	}

	@Test
	void retriesTheUkPenaltyAccrualUniqueConflict() {
		PaymentResponse expected = mock(PaymentResponse.class);
		SQLException uniqueViolation = new SQLException("duplicate key value violates unique constraint "
				+ "\"uk_penalty_accrual\"", "23505");
		when(payments.create(IDEMPOTENCY_KEY, REQUEST))
				.thenThrow(new DataIntegrityViolationException("uk_penalty_accrual", uniqueViolation))
				.thenReturn(expected);

		PaymentResponse actual = retrying.create(IDEMPOTENCY_KEY, REQUEST);

		assertThat(actual).isSameAs(expected);
		verify(payments, times(2)).create(IDEMPOTENCY_KEY, REQUEST);
		assertThat(sleeper.recordedMillis).containsExactly(50L);
	}

	@Test
	void exhaustsAtThreeAttemptsThenThrowsConcurrentModification() {
		when(payments.create(IDEMPOTENCY_KEY, REQUEST))
				.thenThrow(new ObjectOptimisticLockingFailureException(Object.class, "installment-1"));

		assertThatThrownBy(() -> retrying.create(IDEMPOTENCY_KEY, REQUEST))
				.isInstanceOf(PaymentConflictRetriesExhaustedException.class)
				.hasCauseInstanceOf(ObjectOptimisticLockingFailureException.class);

		verify(payments, times(3)).create(IDEMPOTENCY_KEY, REQUEST);
		// Only two waits happen: no backoff after the final, non-retried attempt.
		assertThat(sleeper.recordedMillis).containsExactly(50L, 150L);
	}

	@Test
	void doesNotRetryValidationOrBusinessStateErrors() {
		BadRequestException validationFailure = new BadRequestException("amount must be > 0");
		when(payments.create(IDEMPOTENCY_KEY, REQUEST)).thenThrow(validationFailure);

		assertThatThrownBy(() -> retrying.create(IDEMPOTENCY_KEY, REQUEST))
				.isSameAs(validationFailure);

		verify(payments, times(1)).create(IDEMPOTENCY_KEY, REQUEST);
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
