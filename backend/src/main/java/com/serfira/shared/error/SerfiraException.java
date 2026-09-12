package com.serfira.shared.error;

import org.springframework.http.HttpStatus;

/**
 * Base class for known domain/application errors that map to a typed {@link ErrorCode} and HTTP status.
 * Translating real errors deliberately avoids leaking stack traces to API clients.
 */
public abstract class SerfiraException extends RuntimeException {

	private final ErrorCode code;
	private final HttpStatus status;

	protected SerfiraException(ErrorCode code, HttpStatus status, String message) {
		super(message);
		this.code = code;
		this.status = status;
	}

	protected SerfiraException(ErrorCode code, HttpStatus status, String message, Throwable cause) {
		super(message, cause);
		this.code = code;
		this.status = status;
	}

	public ErrorCode code() {
		return code;
	}

	public HttpStatus status() {
		return status;
	}
}