package com.serfira.settlement.api;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Body of {@code POST /api/v1/settlements/quote} (E1, task T12). A quote needs only the contract to price;
 * the business date is the server clock (Asia/Jakarta), never a client value — a client cannot backdate or
 * future-date a quote (04_GAPS_ADDENDUM.md §10A). The wire name is snake_case ({@code contract_id}) via the
 * global Jackson configuration; the field stays camelCase.
 *
 * @param contractId the contract to settle; must be an ACTIVE contract with a schedule
 */
public record SettlementQuoteRequest(@NotNull UUID contractId) {
}
