package com.serfira.shared.error;

import org.springframework.http.HttpStatus;

/** 409 — the request is valid but conflicts with the current state (e.g. duplicate or constraint violation). */
public final class ConflictException extends SerfiraException {

	public ConflictException(String message) {
		super(ErrorCode.CONFLICT, HttpStatus.CONFLICT, message);
	}
}