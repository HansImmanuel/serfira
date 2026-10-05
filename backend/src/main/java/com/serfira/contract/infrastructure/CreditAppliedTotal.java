package com.serfira.contract.infrastructure;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Σ applied amount for one {@code contract_credit}, used to derive its available balance
 * (available = {@code amount − applied}, Addendum §2.1).
 *
 * @param creditId the credit
 * @param applied  total of its {@code contract_credit_application.amount} rows
 */
public record CreditAppliedTotal(UUID creditId, BigDecimal applied) {
}
