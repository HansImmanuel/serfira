package com.serfira.shared.security;

import com.serfira.shared.error.ErrorCode;
import com.serfira.shared.error.SerfiraException;
import org.springframework.http.HttpStatus;

/**
 * Raised when a raw NIK/phone fails format validation (H-2: NIK = exactly 16 digits,
 * phone = 9–15 digits after {@code 0 → 62} normalization). Mapped to 400
 * {@code VALIDATION_ERROR} by the standard envelope.
 */
public class InvalidPiiDataException extends SerfiraException {

	public InvalidPiiDataException(String message) {
		super(ErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST, message);
	}
}
