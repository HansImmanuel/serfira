package com.serfira.shared.observability;

import java.util.UUID;

import org.slf4j.MDC;

/**
 * Scoped MDC population for structured-logging fields that are flow-specific.
 *
 * <p>Request-wide correlation IDs remain owned by CorrelationIdFilter.
 * This scope manages only module and contract_id, restoring their previous
 * values when closed so nested scopes do not overwrite their parents.
 *
 * <p>Scopes must be closed on the same thread and in LIFO order.
 */
public final class LogContext implements AutoCloseable {

    private final String previousModule;
    private String previousContract;
    private boolean contractCaptured;
    private boolean closed;

    private LogContext(String module) {
        this.previousModule = MDC.get(CorrelationId.MODULE);

        if (module != null && !module.isBlank()) {
            MDC.put(CorrelationId.MODULE, module);
        }
    }

    public static LogContext forModule(String module) {
        return new LogContext(module);
    }

    public LogContext withContract(UUID contractId) {
        ensureOpen();

        if (contractId != null) {
            if (!contractCaptured) {
                previousContract = MDC.get(CorrelationId.CONTRACT_ID);
                contractCaptured = true;
            }

            MDC.put(CorrelationId.CONTRACT_ID, contractId.toString());
        }

        return this;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("LogContext is already closed");
        }
    }

    private static void restore(String key, String previousValue) {
        if (previousValue == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, previousValue);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }

        closed = true;

        if (contractCaptured) {
            restore(CorrelationId.CONTRACT_ID, previousContract);
        }

        restore(CorrelationId.MODULE, previousModule);
    }
}
