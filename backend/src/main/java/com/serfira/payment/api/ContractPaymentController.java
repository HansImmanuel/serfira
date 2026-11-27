package com.serfira.payment.api;

import com.serfira.payment.application.PaymentHistoryQueryService;
import com.serfira.shared.api.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Payment-history read of one contract (task T39). It lives in the {@code payment} module, not in
 * {@code ContractController}, because {@code contract} must never import {@code payment} (ADR-001,
 * 02_TECH_SPEC §1); the contract's existence is checked through {@code ContractLookupPort}.
 *
 * <p>The list is not paged and includes every status, POSTED and VOIDED, ordered {@code paid_at desc,
 * payment_no desc} (Decision 3). It returns the stored allocations of each payment, a voided payment's
 * included as history (Decision 4). Allowed roles are ADMIN_OPERASIONAL and FINANCE; MANAJEMEN reads
 * aggregates only (Addendum §3.4), enforced in the matcher table of
 * {@code ResourceServerSecurityConfiguration} (ADR-015).
 */
@RestController
@RequestMapping("/api/v1/contracts")
@Tag(name = "Payments", description = "Payment receipt, allocation and ledger posting")
public class ContractPaymentController {

	private final PaymentHistoryQueryService history;

	public ContractPaymentController(PaymentHistoryQueryService history) {
		this.history = history;
	}

	@GetMapping("/{id}/payments")
	@Operation(summary = "Payment history of a contract",
			description = """
					Every payment received for the contract, POSTED and VOIDED, newest first (`paid_at desc`,
					then `payment_no desc`). Not paged: there is one row per payment. Each row carries its
					allocations (a voided payment keeps them as history), `excess_amount` (the EXCESS held as
					customer credit) and, once voided, `voided_at` and `void_reason` (otherwise `null`).
					Allowed roles: ADMIN_OPERASIONAL, FINANCE.""")
	@ApiResponses({
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
					description = "The payment history, empty when the contract has no payments"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
					description = "UNAUTHORIZED - missing or invalid bearer token (bad signature, expired, "
							+ "no exp, or a sub that is not a user id)"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
					description = "FORBIDDEN - allowed roles: ADMIN_OPERASIONAL, FINANCE"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
					description = "CONTRACT_NOT_FOUND - no such contract")
	})
	public ApiResponse<List<ContractPaymentResponse>> list(@PathVariable UUID id) {
		return ApiResponse.ok(history.forContract(id));
	}
}
