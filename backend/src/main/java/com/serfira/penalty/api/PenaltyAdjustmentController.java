package com.serfira.penalty.api;

import com.serfira.penalty.application.PenaltyAdjustmentResult;
import com.serfira.penalty.application.PenaltyAdjustmentRetryingService;
import com.serfira.shared.api.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Penalty waive/reduce HTTP surface (E5, task T15; ADR-019 D2, 04_GAPS_ADDENDUM.md §16.4). HTTP concerns
 * only — every rule lives in {@code PenaltyAdjustmentService} and the domain, wrapped by
 * {@link PenaltyAdjustmentRetryingService} for optimistic-lock retries, and every response travels in the
 * standard {@code {data, error}} envelope (TS §2.2).
 *
 * <p>Authorization follows the Addendum §3.4 matrix, enforced in one matcher table in
 * {@code ResourceServerSecurityConfiguration} (ADR-015): recording an adjustment needs ADMIN_OPERASIONAL.
 * The constant below only documents that table for OpenAPI.
 */
@RestController
@RequestMapping("/api/v1/penalty-adjustments")
@Tag(name = "Penalty adjustment", description = "Penalty waive/reduce (writes down effective penalty)")
public class PenaltyAdjustmentController {

	private static final String UNAUTHORIZED_DESCRIPTION = "UNAUTHORIZED - missing or invalid bearer token "
			+ "(bad signature, expired, no exp, or a sub that is not a user id)";
	private static final String FORBIDDEN_ADMIN_ONLY = "FORBIDDEN - allowed role: ADMIN_OPERASIONAL";

	private final PenaltyAdjustmentRetryingService adjustments;

	public PenaltyAdjustmentController(PenaltyAdjustmentRetryingService adjustments) {
		this.adjustments = adjustments;
	}

	@PostMapping
	@Operation(summary = "Waive or reduce recognized penalty on an installment",
			description = """
					Records an append-only penalty adjustment (WAIVE or REDUCE) against an installment and
					posts the correcting journal Dr BEBAN_WAIVER_DENDA / Cr PIUTANG_DENDA for the amount
					(ref_type=PENALTY_WAIVER, ref_id=penalty_adjustment.id, ADR-019 D3). The adjustment reduces
					the installment's effective penalty (effective = gross accrued - Σ adjustments, invariant 9)
					and never drives it below zero; the accrual history stays untouched. The approver is the
					authenticated user (never request-supplied). Allowed role: ADMIN_OPERASIONAL.""")
	@ApiResponses({
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
					description = "Adjustment recorded; returns the adjustment and the effective penalty after it"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
					description = "VALIDATION_ERROR - amount not positive or finer than scale 2, "
							+ "or a required field (contract_id, installment_id, adjustment_type, reason) is missing"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
					description = UNAUTHORIZED_DESCRIPTION),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
					description = FORBIDDEN_ADMIN_ONLY),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
					description = "CONTRACT_NOT_FOUND - no such contract; "
							+ "NOT_FOUND - the installment is not part of the contract"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
					description = "CONTRACT_STATE_INVALID - contract is not ACTIVE; "
							+ "CONFLICT - amount exceeds the installment's effective penalty (would go negative); "
							+ "CONCURRENT_MODIFICATION - a concurrent waiver on the same installment won the "
							+ "race and the retries were exhausted")
	})
	public ApiResponse<PenaltyAdjustmentResponse> adjust(@Valid @RequestBody PenaltyAdjustmentRequest request) {
		PenaltyAdjustmentResult result = adjustments.adjust(request.contractId(), request.installmentId(),
				request.adjustmentType(), request.amount(), request.reason());
		return ApiResponse.ok(PenaltyAdjustmentResponse.from(result));
	}
}
