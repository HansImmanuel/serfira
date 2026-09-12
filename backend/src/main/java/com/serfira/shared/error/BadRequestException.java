package com.serfira.shared.error;

import org.springframework.http.HttpStatus;

/** 400 — the request payload/state is invalid and cannot be processed. */
public final class BadRequestException extends SerfiraException {

	public BadRequestException(String message) {
		super(ErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST, message);
	}
}