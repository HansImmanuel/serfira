package com.serfira.settlement.api;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Body of {@code POST /api/v1/settlements} (E2, task T13). Execution needs only the quote to execute; the
 * {@code Idempotency-Key} is a header, not a body field, and the business date comes from the server clock
 * (Asia/Jakarta), never a client value. The wire name is snake_case ({@code quote_id}) via the global
 * Jackson configuration; the field stays camelCase.
 *
 * @param quoteId the QUOTED settlement quote to execute
 */
public record SettlementExecutionRequest(@NotNull UUID quoteId) {
}
