package com.serfira.shared.error;

import org.springframework.http.HttpStatus;

/**
 * 409 — a concurrent modification prevented the write (optimistic locking). Client should refresh and retry
 * (Addendum §5). Named {@code StaleRecordException} to avoid confusion with
 * {@code java.util.ConcurrentModificationException}.
 */
public final class StaleRecordException extends SerfiraException {

	public StaleRecordException(String message) {
		super(ErrorCode.CONCURRENT_MODIFICATION, HttpStatus.CONFLICT, message);
	}

	public StaleRecordException(String message, Throwable cause) {
		super(ErrorCode.CONCURRENT_MODIFICATION, HttpStatus.CONFLICT, message, cause);
	}
}
