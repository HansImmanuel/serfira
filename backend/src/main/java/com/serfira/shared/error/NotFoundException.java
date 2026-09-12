package com.serfira.shared.error;

import org.springframework.http.HttpStatus;

/** 404 — the requested resource does not exist or is not visible to the caller. */
public final class NotFoundException extends SerfiraException {

	public NotFoundException(String message) {
		super(ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND, message);
	}
}