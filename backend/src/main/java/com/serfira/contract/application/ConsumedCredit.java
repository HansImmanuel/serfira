package com.serfira.contract.application;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One source {@code contract_credit} consumed by a settlement, and how much (E2, task T13, ADR-018 D10).
 * Returned by {@link ContractCreditPort#consumeAllAvailableCredit} so the settlement module can write the
 * matching {@code settlement_credit_application} rows and the {@code TITIPAN_NASABAH} journal line.
 *
 * @param creditId the consumed {@code contract_credit} id
 * @param amount   the available balance of that credit that the settlement consumed, scale-2, {@code > 0}
 */
public record ConsumedCredit(UUID creditId, BigDecimal amount) {
}
