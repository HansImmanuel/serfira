package com.serfira.auth.application;

import org.springframework.http.HttpStatus;

import com.serfira.shared.error.ErrorCode;
import com.serfira.shared.error.SerfiraException;

/**
 * A login attempt against an account inside a brute-force lockout window (T21b, Addendum §3.1).
 *
 * <p>Deliberately distinguishable from {@link AuthenticationFailedException}: the frontend shows
 * "Account locked, try again in X minutes" (`06_FRONTEND_SPEC.md` §2.1) instead of "Bad
 * credentials". That does disclose whether a username exists to anyone willing to spend the
 * threshold attempts, which ADR-022 addendum D12 accepts — usernames are assigned by an
 * administrator rather than held secret, and the D9 Argon2 semaphore bounds the probing rate.
 */
public class AccountLockedException extends SerfiraException {

	public AccountLockedException(long minutesRemaining) {
		super(ErrorCode.ACCOUNT_LOCKED, HttpStatus.LOCKED,
				"Account locked; try again in " + Math.max(1, minutesRemaining) + " minute(s)");
	}
}
