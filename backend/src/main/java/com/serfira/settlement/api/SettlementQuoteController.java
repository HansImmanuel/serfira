package com.serfira.settlement.api;

import com.serfira.settlement.application.SettlementQuoteRetryingService;
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
 * Settlement quote HTTP surface (E1, task T12, ADR-018). HTTP concerns only — pricing lives in the pure
 * {@code SettlementQuoteEngine} and the application service; the quote posts no journal and resolves no
 * money (execution is T13).
 *
 * <p>Authorization follows the Addendum §3.4 matrix, enforced in the single matcher table in
 * {@code ResourceServerSecurityConfiguration} (ADR-015): quoting is ADMIN_OPERASIONAL only. The constant
 * below only documents that table for OpenAPI.
 */
@RestController
@RequestMapping("/api/v1/settlements")
@Tag(name = "Settlement", description = "Early settlement (pelunasan dipercepat): quote and execution")
public class SettlementQuoteController {

	private static final String UNAUTHORIZED_DESCRIPTION = "UNAUTHORIZED - missing or invalid bearer token "
			+ "(bad signature, expired, no exp, or a sub that is not a user id)";
	private static final String FORBIDDEN_ADMIN_ONLY = "FORBIDDEN - allowed role: ADMIN_OPERASIONAL";

	private final SettlementQuoteRetryingService quotes;

	public SettlementQuoteController(SettlementQuoteRetryingService quotes) {
		this.quotes = quotes;
	}

	@PostMapping("/quote")
	@Operation(summary = "Price an early-settlement quote",
			description = """
					Prices an immutable settlement quote for an ACTIVE contract (ADR-018). In one transaction the
					server first bills due interest and accrues due penalty through today (accrue-before-resolve,
					ADR-013), then prices the components: outstanding principal (incl. future periods),
					recognized-but-unpaid interest, the active period's running interest (ACT/30, capped at 30 days),
					effective remaining penalty, and the net future interest after a 50% rebate on the unearned half,
					plus a flat admin fee. gross_amount is their sum; cash_due is gross_amount minus credit_used
					(credit is snapshotted but consumed only at execution, so credit_used is 0 here). The quote is
					stored with a TTL (valid_until) and the contract version it priced against; no journal is posted
					and no money moves. Allowed role: ADMIN_OPERASIONAL.""")
	@ApiResponses({
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
					description = "The priced quote (components, gross_amount, cash_due, valid_until, contract_version)"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
					description = "VALIDATION_ERROR - contract_id is missing or not a UUID"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
					description = UNAUTHORIZED_DESCRIPTION),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
					description = FORBIDDEN_ADMIN_ONLY),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
					description = "CONTRACT_NOT_FOUND - no such contract"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
					description = "CONTRACT_STATE_INVALID - contract is not ACTIVE; "
							+ "CONCURRENT_MODIFICATION - a concurrent job conflict persisted across retries")
	})
	public ApiResponse<SettlementQuoteResponse> quote(@Valid @RequestBody SettlementQuoteRequest request) {
		return ApiResponse.ok(quotes.quote(request.contractId()));
	}
}
