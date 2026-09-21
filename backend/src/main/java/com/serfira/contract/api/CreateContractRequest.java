package com.serfira.contract.api;

import com.serfira.contract.domain.InterestScheme;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Create a DRAFT contract (PRD C-1, DM §1.3, FE §2.3). Requires an {@code Idempotency-Key} header
 * (TS §2.2/§2.5) because a double-submitted create must not produce two contracts.
 *
 * <p>The request is rejected unless the terms are sane (story B5 decision D8): money at scale ≤ 2,
 * rate at scale ≤ 4 within {@code 0..1}, {@code 0 <= down_payment < asset_price}. Those are
 * aggregated rules that a field annotation cannot express, so they are enforced in
 * {@code ContractCommandService} and reported as 400 {@code VALIDATION_ERROR}.
 *
 * <p>There is deliberately <b>no</b> {@code principal} field: it is computed server-side as
 * {@code asset_price − down_payment}, so the DM §1.3 invariant cannot be violated by a client.
 *
 * @param plannedStartDate the date the business intends to start (PRD C-1 "tanggal mulai"); stored
 *                         as {@code planned_start_date}. Activation pins the effective
 *                         {@code start_date}, defaulting to this value when no override is sent.
 */
public record CreateContractRequest(
		@Valid @NotNull CreateCustomerRequest customer,
		@Valid @NotNull CreateAssetRequest asset,
		@NotNull @DecimalMin("0.01") BigDecimal assetPrice,
		@NotNull @DecimalMin("0.00") BigDecimal downPayment,
		@Min(1) @Max(120) int tenorMonths,
		@NotNull InterestScheme interestScheme,
		@NotNull @DecimalMin("0.0000") @DecimalMax("1.0000") BigDecimal interestRate,
		@NotNull LocalDate plannedStartDate) {
}