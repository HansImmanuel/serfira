package com.serfira.contract.api;

import com.serfira.contract.application.ContractCreditQueryService;
import com.serfira.contract.application.ContractCreditRetryingService;
import com.serfira.shared.api.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Contract-credit HTTP surface (E3, task T14; PRD P-4, 04_GAPS_ADDENDUM.md §2.5). HTTP concerns only —
 * every rule lives in the application/domain layers, and every response travels in the standard
 * {@code {data, error}} envelope (TS §2.2).
 *
 * <p>Authorization follows the Addendum §3.4 matrix, enforced in one matcher table in
 * {@code ResourceServerSecurityConfiguration} (ADR-015): applying credit needs ADMIN_OPERASIONAL;
 * reading the balance needs ADMIN_OPERASIONAL or FINANCE. The constants below only document that table
 * for OpenAPI.
 */
@RestController
@RequestMapping("/api/v1/contracts")
@Tag(name = "Contract credit", description = "Customer credit (TITIPAN_NASABAH) balance and application")
public class ContractCreditController {

	private static final String UNAUTHORIZED_DESCRIPTION = "UNAUTHORIZED - missing or invalid bearer token "
			+ "(bad signature, expired, no exp, or a sub that is not a user id)";
	private static final String FORBIDDEN_ADMIN_ONLY = "FORBIDDEN - allowed role: ADMIN_OPERASIONAL";
	private static final String FORBIDDEN_READ_ROLES = "FORBIDDEN - allowed roles: ADMIN_OPERASIONAL, FINANCE";

	private final ContractCreditRetryingService commands;
	private final ContractCreditQueryService queries;

	public ContractCreditController(ContractCreditRetryingService commands, ContractCreditQueryService queries) {
		this.commands = commands;
		this.queries = queries;
	}

	@PostMapping("/{id}/credit/apply")
	@Operation(summary = "Apply available customer credit to recognized receivable",
			description = """
					Applies the contract's available credit (overpayment held as TITIPAN_NASABAH) to its oldest
					due installment with recognized receivable, reusing the payment waterfall
					PENALTY -> INTEREST -> PRINCIPAL, oldest due first (ADR-009). Credit is never auto-applied;
					it moves only on this call (PRD P-4).
					`amount` is optional: supply it to apply a partial amount (<= the available balance), or omit
					it to apply the full available balance. Only recognized receivable is reduced — future
					unrecognized interest is never credited. One balanced journal entry is posted per call
					(Dr TITIPAN_NASABAH / Cr PIUTANG_DENDA|PIUTANG_BUNGA|PIUTANG_POKOK, ref_type=CREDIT_APPLICATION),
					installment paid_amount rises, and a contract_credit_application row is recorded. A credit is
					flipped AVAILABLE -> APPLIED only when its balance reaches 0. Allowed role: ADMIN_OPERASIONAL.""")
	@ApiResponses({
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
					description = "Credit applied; returns the amount applied, balance after, and the applications made"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
					description = "VALIDATION_ERROR - amount not positive or finer than scale 2"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
					description = UNAUTHORIZED_DESCRIPTION),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
					description = FORBIDDEN_ADMIN_ONLY),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
					description = "CONTRACT_NOT_FOUND - no such contract"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
					description = "CONTRACT_STATE_INVALID - contract is not ACTIVE; "
							+ "CONFLICT - requested amount exceeds the available balance; "
							+ "CREDIT_NOT_APPLICABLE - no available credit, or no due installment with recognized "
							+ "receivable to apply against; "
							+ "CONCURRENT_MODIFICATION - a concurrent job/payment conflict persisted across retries")
	})
	public ApiResponse<CreditApplicationResponse> apply(
			@PathVariable UUID id,
			@Valid @RequestBody(required = false) ApplyCreditRequest request) {
		return ApiResponse.ok(commands.apply(id, request == null ? null : request.amount()));
	}

	@GetMapping("/{id}/credit")
	@Operation(summary = "Contract credit balance and application history",
			description = """
					The contract's total booked credit, its available balance (Σ over AVAILABLE credits of
					amount − Σ applications), and every credit application made, oldest first (Addendum §2.5).
					Allowed roles: ADMIN_OPERASIONAL, FINANCE.""")
	@ApiResponses({
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
					description = "The credit balance and application history"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
					description = UNAUTHORIZED_DESCRIPTION),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
					description = FORBIDDEN_READ_ROLES),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
					description = "CONTRACT_NOT_FOUND - no such contract")
	})
	public ApiResponse<CreditBalanceResponse> credit(@PathVariable UUID id) {
		return ApiResponse.ok(queries.credit(id));
	}
}
