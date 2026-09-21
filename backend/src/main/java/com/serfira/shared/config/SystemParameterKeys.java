package com.serfira.shared.config;

/**
 * Keys of the global configuration rows documented in 04_GAPS_ADDENDUM.md §1.3. Only keys that a
 * story actually reads are listed — later stories add their own key when they start reading it.
 */
public final class SystemParameterKeys {

	/** Grace period in days before penalty accrual starts (DM §1.3 snapshot). */
	public static final String DEFAULT_GRACE_PERIOD_DAYS = "DEFAULT_GRACE_PERIOD_DAYS";

	/** Daily penalty rate as a decimal fraction (0.1% = {@code 0.0010}). */
	public static final String DEFAULT_PENALTY_RATE_DAILY = "DEFAULT_PENALTY_RATE_DAILY";

	/** Retention of an idempotency record before it may be cleaned up (TS §2.5). */
	public static final String IDEMPOTENCY_KEY_RETENTION_DAYS = "IDEMPOTENCY_KEY_RETENTION_DAYS";

	private SystemParameterKeys() {
	}
}