package com.serfira.reporting.api;

import com.serfira.reporting.application.AgingReportService;
import com.serfira.shared.api.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Aging report HTTP surface (PRD D-2, 06_FRONTEND_SPEC.md §2.9). HTTP concerns only — bucketing and the
 * outstanding/DPD definitions live in the application/domain layers and the {@code contract} module.
 *
 * <p>Authorization follows the Addendum §3.4 matrix, enforced in the single matcher table in
 * {@code ResourceServerSecurityConfiguration} (ADR-015): a read allowed to ADMIN_OPERASIONAL, FINANCE and
 * MANAJEMEN. The constant below only documents that table for OpenAPI.
 */
@RestController
@RequestMapping("/api/v1/reports")
@Validated
@Tag(name = "Reports", description = "Portfolio servicing reports")
public class AgingReportController {

	private static final String UNAUTHORIZED_DESCRIPTION = "UNAUTHORIZED - missing or invalid bearer token "
			+ "(bad signature, expired, no exp, or a sub that is not a user id)";
	private static final String FORBIDDEN_READ_ROLES = "FORBIDDEN - allowed roles: ADMIN_OPERASIONAL, FINANCE, MANAJEMEN";

	private final AgingReportService reports;

	public AgingReportController(AgingReportService reports) {
		this.reports = reports;
	}

	@GetMapping("/aging")
	@Operation(summary = "Aging report",
			description = """
					Delinquency buckets (Current, 1-30, 31-60, 61-90, >90 days past due) of every ACTIVE contract's
					outstanding, as a portfolio total and per contract (PRD D-2).
					Outstanding is principal residual + recognized interest residual + penalty residual; future
					unrecognized interest is excluded, and penalty adjustments are not yet subtracted (no waiver flow
					exists until story E5). At both the portfolio and the contract level the five buckets sum to the
					outstanding. `as_of` defaults to today and must be today — historical reconstruction is not
					supported in Phase 1. The per-contract rows are paged; the portfolio total spans all contracts.""")
	@ApiResponses({
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
					description = "The aging report (portfolio total plus one page of per-contract rows)"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
					description = "INVALID_AS_OF_DATE - as_of is not today; "
							+ "VALIDATION_ERROR - paging out of range or an unparseable as_of"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
					description = UNAUTHORIZED_DESCRIPTION),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
					description = FORBIDDEN_READ_ROLES)
	})
	public ApiResponse<AgingReportResponse> aging(
			@Parameter(description = "Business date to age against; defaults to today and must be today")
			@RequestParam(name = "as_of", required = false)
			@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
			@Parameter(description = "0-based page index for the per-contract rows")
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@Parameter(description = "Page size (1..100)")
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
		return ApiResponse.ok(reports.aging(asOf, page, size));
	}
}
