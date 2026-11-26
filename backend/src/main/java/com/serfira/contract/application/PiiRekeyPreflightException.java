package com.serfira.contract.application;

/**
 * A re-keying run refused to write (T28b): {@code EXECUTE} while new values are still written as {@code v1}, or a
 * census that found unreadable or duplicate rows. The message carries settings and counts only, never a value.
 */
public class PiiRekeyPreflightException extends IllegalStateException {

	PiiRekeyPreflightException(String message) {
		super(message);
	}
}
