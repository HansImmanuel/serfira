package com.serfira.auth.application;

import org.springframework.http.HttpStatus;

import com.serfira.shared.error.ErrorCode;
import com.serfira.shared.error.SerfiraException;

/**
 * A login attempt that could not be authenticated (T21a, Addendum §3.3).
 *
 * <p>Every cause — unknown username, wrong password, inactive account, non-interactive principal —
 * returns the same {@code 401 UNAUTHORIZED} envelope and the same message, so the response does not
 * reveal whether a username exists or an account is disabled.
 */
public class AuthenticationFailedException extends SerfiraException {

	public AuthenticationFailedException() {
		super(ErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED, "Bad credentials");
	}
}
