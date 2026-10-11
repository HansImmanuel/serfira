package com.serfira.settlement.api;

import com.serfira.settlement.application.SettlementExecutionRetryingService;
import com.serfira.shared.api.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Settlement execution HTTP surface (E2, task T13, ADR-018). HTTP concerns only — re-pricing, revalidation,
 * credit consumption, journal posting and the contract close live in the application/domain layers, and
 * every response travels in the standard {@code {data, error}} envelope (TS §2.2).
 *
 * <p>Only ADMIN_OPERASIONAL may execute a settlement (Addendum §3.4), enforced in the matcher table of
 * {@code ResourceServerSecurityConfiguration} (ADR-015). Kept separate from
 * {@link SettlementQuoteController} so quote and execution stay distinct concerns under the same
 * {@code /api/v1/settlements} base path and {@code Settlement} OpenAPI tag.
 */
@RestController
@RequestMapping("/api/v1/settlements")
@Tag(name = "Settlement", description = "Early settlement (pelunasan dipercepat): quote and execution")
public class SettlementExecutionController {

	private final SettlementExecutionRetryingService settlements;

	public SettlementExecutionController(SettlementExecutionRetryingService settlements) {
		this.settlements = settlements;
	}

	@PostMapping
	@Operation(summary = "Execute a settlement quote into an immutable settlement",
			description = """
					Executes a QUOTED settlement quote (ADR-018). In one transaction the server bills due interest
					and accrues due penalty through today (accrue-before-resolve, ADR-013), re-prices the components
					and revalidates them — and the contract version — against the stored quote snapshot. It then
					consumes all AVAILABLE customer credit, resolves the recognized receivable per installment
					(penalty -> interest -> principal, oldest first), posts one balanced SETTLEMENT journal entry
					(cash + consumed credit against the cleared receivables, net future interest and the admin fee
					as income; no rebate line), moves every open installment to SETTLED and closes the contract
					SETTLEMENT. Repeating the request with the same Idempotency-Key replays the stored settlement
					instead of settling twice. Allowed role: ADMIN_OPERASIONAL.""")
	@ApiResponses({
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201",
					description = "Settlement executed (or the original one, replayed for an identical retry)"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
					description = "VALIDATION_ERROR - missing/invalid body (quote_id) or missing Idempotency-Key"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
					description = "UNAUTHORIZED - missing or invalid bearer token (bad signature, expired, "
							+ "no exp, or a sub that is not a user id)"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
					description = "FORBIDDEN - allowed role: ADMIN_OPERASIONAL"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
					description = "NOT_FOUND - no such quote; CONTRACT_NOT_FOUND - the quote's contract is gone"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
					description = "STALE_SETTLEMENT_QUOTE - the contract changed since the quote (version or a "
							+ "recomputed component differs); SETTLEMENT_QUOTE_EXPIRED - the quote is past its TTL; "
							+ "SETTLEMENT_QUOTE_ALREADY_EXECUTED - the quote was already executed; "
							+ "CREDIT_EXCEEDS_SETTLEMENT - available credit is larger than the settlement gross; "
							+ "CONTRACT_STATE_INVALID - the contract is not ACTIVE; CONFLICT - the Idempotency-Key "
							+ "was already used for a different request; IDEMPOTENCY_KEY_EXPIRED - the Idempotency-Key "
							+ "is spent (mint a fresh key); CONCURRENT_MODIFICATION - a concurrent daily servicing "
							+ "run kept colliding after 3 attempts; refresh and retry")
	})
	public ResponseEntity<ApiResponse<SettlementExecutionResponse>> execute(
			@Parameter(description = "Endpoint-scoped retry key (TS §2.2/§2.5)", required = true)
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@Valid @RequestBody SettlementExecutionRequest request) {
		SettlementExecutionResponse executed = settlements.execute(idempotencyKey, request);
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(executed));
	}
}
