package com.serfira.shared.error;

import org.springframework.http.HttpStatus;

/**
 * 409 — a concurrent modification prevented the write (optimistic locking). Client should refresh and retry
 * (Addendum §5).
 */
public final class ConcurrentModificationException extends SerfiraException {

	public ConcurrentModificationException(String message) {
		super(ErrorCode.CONCURRENT_MODIFICATION, HttpStatus.CONFLICT, message);
	}

	public ConcurrentModificationException(String message, Throwable cause) {
		super(ErrorCode.CONCURRENT_MODIFICATION, HttpStatus.CONFLICT, message, cause);
	}
}