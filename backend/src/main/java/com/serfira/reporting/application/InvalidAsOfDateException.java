package com.serfira.reporting.application;

import com.serfira.shared.error.ErrorCode;
import com.serfira.shared.error.SerfiraException;
import org.springframework.http.HttpStatus;

/**
 * 400 — the aging report was asked for an {@code as_of} other than today. Phase 1 does not reconstruct
 * history (ADR-013 A-6): the parameter is rejected rather than silently ignored.
 */
public final class InvalidAsOfDateException extends SerfiraException {

	public InvalidAsOfDateException(String message) {
		super(ErrorCode.INVALID_AS_OF_DATE, HttpStatus.BAD_REQUEST, message);
	}
}
