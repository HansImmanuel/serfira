package com.serfira.contract.api;

import java.time.LocalDate;

/**
 * Activation request (PRD C-2, DM §1.3). The body is optional: with no body, or with a null
 * {@code startDate}, the effective start date defaults to the contract's
 * {@code planned_start_date} — which is what the frontend's confirmation dialog sends
 * (FE §2.4 has no date input). An explicit value overrides it and records the real disbursement date.
 *
 * @param startDate effective activation date; {@code null} means "use the planned start date"
 */
public record ActivateContractRequest(LocalDate startDate) {
}