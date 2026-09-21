package com.serfira.shared.idempotency;

/**
 * Outcome of an idempotent mutation attempt.
 *
 * @param response the response of this attempt (freshly produced, or the stored response of the
 *                 earlier execution when {@code replayed} is true)
 * @param replayed {@code true} when the mutation was NOT executed because an identical request under
 *                 the same key already committed (TS §2.5)
 * @param <T>      response payload type
 */
public record IdempotentResult<T>(T response, boolean replayed) {

	public static <T> IdempotentResult<T> executed(T response) {
		return new IdempotentResult<>(response, false);
	}

	public static <T> IdempotentResult<T> replayed(T response) {
		return new IdempotentResult<>(response, true);
	}
}