package com.serfira.shared.error;

import org.springframework.http.HttpStatus;

/** 403 — the caller is not allowed to perform this action (default-deny authorization). */
public final class ForbiddenException extends SerfiraException {

	public ForbiddenException(String message) {
		super(ErrorCode.FORBIDDEN, HttpStatus.FORBIDDEN, message);
	}
}