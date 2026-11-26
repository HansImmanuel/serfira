package com.serfira.shared.outbox;

/**
 * Outcome of one claimed batch: rows claimed, rows the sink accepted, and rows it refused (whether they stay
 * {@code PENDING} for a retry or just became {@code FAILED}).
 */
public record OutboxBatchResult(int claimed, int sent, int failed) {
}
