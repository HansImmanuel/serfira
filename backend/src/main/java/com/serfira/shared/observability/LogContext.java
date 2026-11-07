package com.serfira.shared.observability;

import java.util.UUID;

import org.slf4j.MDC;

/**
 * Scoped MDC population for the structured-logging fields that are not request-wide (T20, Addendum §10).
 *
 * <p>The request id is owned by {@link CorrelationIdFilter} for the whole request. {@code contract_id} and
 * {@code module}, by contrast, are only meaningful inside a particular flow (a payment write, a daily-job
 * step), so a flow opens a scope around that work and the field is cleared when the scope closes — it never
 * leaks onto an unrelated log line on the same pooled thread.
 *
 * <p>Usage:
 * <pre>{@code
 * try (LogContext ignored = LogContext.forModule("payment").withContract(contractId)) {
 *     // log lines here carry module=payment and contract_id=...
 * }
 * }</pre>
 *
 * <p>Only ids and safe metadata go in the MDC — never PII, tokens, secrets, or financial amounts
 * (steering 60-security / 35-coding-standards).
 */
public final class LogContext implements AutoCloseable {

	private final boolean ownsModule;
	private final boolean ownsContract;

	private LogContext(boolean ownsModule, boolean ownsContract) {
		this.ownsModule = ownsModule;
		this.ownsContract = ownsContract;
	}

	/** Opens a scope that tags log lines with the given module name. */
	public static LogContext forModule(String module) {
		boolean owns = put(CorrelationId.MODULE, module);
		return new LogContext(owns, false);
	}

	/** Adds a {@code contract_id} to the current scope. */
	public LogContext withContract(UUID contractId) {
		boolean owns = contractId != null && put(CorrelationId.CONTRACT_ID, contractId.toString());
		return new LogContext(this.ownsModule, this.ownsContract || owns);
	}

	private static boolean put(String key, String value) {
		if (value == null || value.isBlank()) {
			return false;
		}
		MDC.put(key, value);
		return true;
	}

	@Override
	public void close() {
		if (ownsContract) {
			MDC.remove(CorrelationId.CONTRACT_ID);
		}
		if (ownsModule) {
			MDC.remove(CorrelationId.MODULE);
		}
	}
}
