package com.serfira.reporting.api;

import com.serfira.reporting.application.OutstandingReportService;
import com.serfira.shared.api.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Outstanding report HTTP surface (T36, PRD R-1, 06_FRONTEND_SPEC.md §2.10). HTTP concerns only — the metric
 * definitions are ADR-026's and are computed in the application layer from the owning modules' ports.
 *
 * <p>Authorization follows the Addendum §3.4 matrix, enforced in {@code ResourceServerSecurityConfiguration}
 * (ADR-015): a read allowed to ADMIN_OPERASIONAL, FINANCE and MANAJEMEN.
 */
@RestController
@RequestMapping("/api/v1/reports")
@Tag(name = "Reports", description = "Portfolio servicing reports")
public class OutstandingReportController {

	private static final String UNAUTHORIZED_DESCRIPTION = "UNAUTHORIZED - missing or invalid bearer token "
			+ "(bad signature, expired, no exp, or a sub that is not a user id)";
	private static final String FORBIDDEN_READ_ROLES = "FORBIDDEN - allowed roles: ADMIN_OPERASIONAL, FINANCE, MANAJEMEN";

	private final OutstandingReportService reports;

	public OutstandingReportController(OutstandingReportService reports) {
		this.reports = reports;
	}

	@GetMapping("/outstanding")
	@Operation(summary = "Outstanding report (management metrics)",
			description = """
					PRD R-1 management metrics, defined in ADR-026.
					- total_outstanding (D2): outstanding of every installment of every ACTIVE contract (principal \
					residual + recognized interest residual + penalty net of adjustments; future unrecognized \
					interest excluded). Equals the aging report's portfolio total_outstanding.
					- accounts_receivable (D3): the part of it on installments already due (due_date <= as_of), \
					including those still inside grace. Not the same as overdue.
					- npl (D4): ACTIVE contracts with an owed installment more than 90 days past due. Their whole \
					outstanding counts, undue principal included, so npl.outstanding is the sum of the aging rows \
					with dpd_over_90 > 0.
					- collection (D5): for installments due from the first of as_of's month to as_of on non-DRAFT \
					contracts, collected = POSTED payment allocations (penalty, interest, principal; voided payments, \
					EXCESS, credit applications and settlements excluded) and due = recognized total net of penalty \
					adjustments minus settled amounts (written-off amounts stay in due).
					- settlement_cash (D6): cash_received of settlements executed in the same period; credit_used \
					is excluded and settlement is never part of the collection rate.
					Money is an exact decimal string. A ratio is a scale-4 fraction rounded HALF_UP ("0.0150" = \
					1.5%), or null when its denominator is zero.
					`as_of` defaults to today and must be today. The metrics come from separate read-only reads, so \
					a payment committing between them can skew one response by at most that payment; the report \
					is not a ledger.""")
	@ApiResponses({
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
					description = "The outstanding report"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
					description = "INVALID_AS_OF_DATE - as_of is not today; VALIDATION_ERROR - an unparseable as_of"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
					description = UNAUTHORIZED_DESCRIPTION),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
					description = FORBIDDEN_READ_ROLES)
	})
	public ApiResponse<OutstandingReportResponse> outstanding(
			@Parameter(description = "Business date of the report; defaults to today and must be today")
			@RequestParam(name = "as_of", required = false)
			@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
		return ApiResponse.ok(OutstandingReportResponse.from(reports.outstanding(asOf)));
	}
}
