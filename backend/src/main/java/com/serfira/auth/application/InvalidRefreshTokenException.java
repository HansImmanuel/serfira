package com.serfira.auth.application;

import org.springframework.http.HttpStatus;

import com.serfira.shared.error.ErrorCode;
import com.serfira.shared.error.SerfiraException;

/**
 * A refresh token that cannot be exchanged (T21b, Addendum §3.3).
 *
 * <p>Covers every cause with one answer — unknown, expired, already rotated (replay), revoked by
 * logout, or belonging to a user who has since been deactivated — so the response cannot be used
 * to distinguish a stolen token from a stale one. The client's only correct reaction is the same
 * in all cases: discard the refresh token and log in again.
 */
public class InvalidRefreshTokenException extends SerfiraException {

	public InvalidRefreshTokenException() {
		super(ErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED, "Invalid or expired refresh token");
	}
}
