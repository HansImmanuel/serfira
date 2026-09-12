package com.serfira.shared.error;

/**
 * Machine-readable error codes from the standard envelope. Module-specific codes (payments, settlement, …)
 * extend this list as those modules are built.
 */
public enum ErrorCode {
	VALIDATION_ERROR,
	NOT_FOUND,
	CONFLICT,
	CONCURRENT_MODIFICATION,
	FORBIDDEN,
	INTERNAL_ERROR
}