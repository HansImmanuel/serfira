package com.serfira.contract.application;

import com.serfira.contract.api.WriteOffContractRequest;
import com.serfira.contract.api.WriteOffResponse;
import com.serfira.contract.domain.ContractStateException;
import com.serfira.shared.concurrency.Sleeper;
import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

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
 * The retry policy of the write-off (F5, task T19), mirroring {@code SettlementExecutionRetryingService}:
 * attempt count, backoff sequence, and the retryable/non-retryable split, using an injected {@link Sleeper}
 * so the suite runs instantly. Two failures are retryable — an optimistic-lock conflict and the
 * {@code uk_penalty_accrual} write-off-versus-job accrual race; every other failure (a business-state error,
 * an unrelated {@code 23505}) is rethrown on the first attempt.
 */
class ContractWriteOffRetryingServiceTest {

	private static final UUID CONTRACT_ID = UUID.randomUUID();
	private static final String IDEMPOTENCY_KEY = "wo-key-1";
	private static final WriteOffContractRequest REQUEST = new WriteOffContractRequest("collection decision");

	private static final String SQL_STATE_UNIQUE_VIOLATION = "23505";

	private final ContractWriteOffCommandService commands = mock(ContractWriteOffCommandService.class);
	private final RecordingSleeper sleeper = new RecordingSleeper();
	private final ContractWriteOffRetryingService retrying = new ContractWriteOffRetryingService(commands, sleeper);

	@Test
	void succeedsOnFirstAttemptWithoutSleeping() {
		WriteOffResponse expected = mock(WriteOffResponse.class);
		when(commands.writeOff(CONTRACT_ID, IDEMPOTENCY_KEY, REQUEST)).thenReturn(expected);

		WriteOffResponse actual = retrying.writeOff(CONTRACT_ID, IDEMPOTENCY_KEY, REQUEST);

		assertThat(actual).isSameAs(expected);
		verify(commands, times(1)).writeOff(CONTRACT_ID, IDEMPOTENCY_KEY, REQUEST);
		assertThat(sleeper.recordedMillis).isEmpty();
	}

	@Test
	void retriesOptimisticLockAndAccrualRaceWithTheDocumentedBackoffThenSucceeds() {
		WriteOffResponse expected = mock(WriteOffResponse.class);
		when(commands.writeOff(CONTRACT_ID, IDEMPOTENCY_KEY, REQUEST))
				.thenThrow(new ObjectOptimisticLockingFailureException(Object.class, "installment-1"))
				.thenThrow(penaltyAccrualRace())
				.thenReturn(expected);

		WriteOffResponse actual = retrying.writeOff(CONTRACT_ID, IDEMPOTENCY_KEY, REQUEST);

		assertThat(actual).isSameAs(expected);
		verify(commands, times(3)).writeOff(CONTRACT_ID, IDEMPOTENCY_KEY, REQUEST);
		assertThat(sleeper.recordedMillis).containsExactly(50L, 150L);
	}

	@Test
	void exhaustsAtThreeAttemptsThenThrowsConcurrentModification() {
		when(commands.writeOff(CONTRACT_ID, IDEMPOTENCY_KEY, REQUEST))
				.thenThrow(new ObjectOptimisticLockingFailureException(Object.class, "installment-1"));

		assertThatThrownBy(() -> retrying.writeOff(CONTRACT_ID, IDEMPOTENCY_KEY, REQUEST))
				.isInstanceOf(WriteOffRetriesExhaustedException.class)
				.hasCauseInstanceOf(ObjectOptimisticLockingFailureException.class);

		verify(commands, times(3)).writeOff(CONTRACT_ID, IDEMPOTENCY_KEY, REQUEST);
		assertThat(sleeper.recordedMillis).containsExactly(50L, 150L);
	}

	@Test
	void doesNotRetryBusinessStateErrors() {
		ContractStateException notActive = new ContractStateException("contract is TERMINATED");
		when(commands.writeOff(CONTRACT_ID, IDEMPOTENCY_KEY, REQUEST)).thenThrow(notActive);

		assertThatThrownBy(() -> retrying.writeOff(CONTRACT_ID, IDEMPOTENCY_KEY, REQUEST))
				.isSameAs(notActive);

		verify(commands, times(1)).writeOff(CONTRACT_ID, IDEMPOTENCY_KEY, REQUEST);
		assertThat(sleeper.recordedMillis).isEmpty();
	}

	@Test
	void doesNotRetryAnUnrelatedUniqueViolation() {
		// A 23505 that is not the accrual race (e.g. a document-number collision) is not a race a retry can
		// clear, so it is rethrown on the first attempt.
		DataIntegrityViolationException unrelated = uniqueViolation(
				"ERROR: duplicate key value violates unique constraint \"uk_contract_no\"");
		when(commands.writeOff(CONTRACT_ID, IDEMPOTENCY_KEY, REQUEST)).thenThrow(unrelated);

		assertThatThrownBy(() -> retrying.writeOff(CONTRACT_ID, IDEMPOTENCY_KEY, REQUEST))
				.isSameAs(unrelated);

		verify(commands, times(1)).writeOff(CONTRACT_ID, IDEMPOTENCY_KEY, REQUEST);
		assertThat(sleeper.recordedMillis).isEmpty();
	}

	@Test
	void optimisticLockAndAccrualRaceAreRetryableButOthersAreNot() {
		assertThat(ContractWriteOffRetryingService.isRetryable(
				new ObjectOptimisticLockingFailureException("installment", 1L))).isTrue();
		assertThat(ContractWriteOffRetryingService.isRetryable(new OptimisticLockException("stale"))).isTrue();
		assertThat(ContractWriteOffRetryingService.isRetryable(penaltyAccrualRace())).isTrue();
		assertThat(ContractWriteOffRetryingService.isRetryable(uniqueViolation(
				"duplicate key value violates unique constraint \"uk_contract_no\""))).isFalse();
		assertThat(ContractWriteOffRetryingService.isRetryable(new IllegalStateException("bad"))).isFalse();
		assertThat(ContractWriteOffRetryingService.isRetryable(null)).isFalse();
	}

	private static DataIntegrityViolationException penaltyAccrualRace() {
		return uniqueViolation("ERROR: duplicate key value violates unique constraint \"uk_penalty_accrual\"");
	}

	private static DataIntegrityViolationException uniqueViolation(String message) {
		SQLException sql = new SQLException(message, SQL_STATE_UNIQUE_VIOLATION);
		return new DataIntegrityViolationException("could not execute statement", sql);
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
