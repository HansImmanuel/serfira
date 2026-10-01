package com.serfira.payment.api;

import com.serfira.payment.application.PaymentRetryingService;
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
 * Payment HTTP surface (PRD P-1–P-4, DM §2). HTTP concerns only — allocation, installment resolution
 * and posting live in the application/domain layers, and every response travels in the standard
 * {@code {data, error}} envelope (TS §2.2).
 *
 * <p>Only ADMIN_OPERASIONAL may post a payment (Addendum §3.4), enforced in the matcher table of
 * {@code ResourceServerSecurityConfiguration} (ADR-015). The paying operator is the JWT subject, and that
 * principal is what the audit columns record.
 *
 * <p>No {@code Location} header is returned with the 201: the payment representation is the response
 * body, and there is no {@code GET /payments/{id}} yet (the statement/read surface is C5). Pointing a
 * header at a route that does not exist would be a lie a client could follow.
 */
@RestController
@RequestMapping("/api/v1/payments")
@Tag(name = "Payments", description = "Payment receipt, allocation and ledger posting")
public class PaymentController {

	private final PaymentRetryingService payments;

	public PaymentController(PaymentRetryingService payments) {
		this.payments = payments;
	}

	@PostMapping
	@Operation(summary = "Receive a payment and allocate it to the contract's due installments",
			description = """
					Records money received and allocates it with the documented waterfall: oldest due installment
					first, inside it `penalty -> interest -> principal` (PRD P-2/P-3). Only installments whose
					`due_date` has been reached are resolved; the remainder becomes customer credit
					(`EXCESS`, credited to `TITIPAN_NASABAH`) and is never applied to future installments
					automatically (PRD P-4).
					`paid_at` is taken from the server clock, so the client cannot backdate or future-date a payment.
					The whole operation — payment, allocations, installment resolution and journal entry — commits
					atomically, and repeating the request with the same `Idempotency-Key` replays the original result
					instead of receiving the money twice.
					`amount` must be money at scale <= 2; a finer value is rejected rather than rounded.""")
	@ApiResponses({
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201",
					description = "Payment posted (or the original one, replayed for an identical retry)"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
					description = "VALIDATION_ERROR - missing/invalid payload, missing Idempotency-Key, "
							+ "amount not positive scale-2 money"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
					description = "UNAUTHORIZED - missing or invalid bearer token (bad signature, expired, "
							+ "no exp, or a sub that is not a user id)"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
					description = "FORBIDDEN - allowed role: ADMIN_OPERASIONAL"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
					description = "CONTRACT_NOT_FOUND - no such contract"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
					description = "CONTRACT_STATE_INVALID - the contract is not ACTIVE (draft, closed or "
							+ "terminated) or has no schedule; CONFLICT - the Idempotency-Key was already "
							+ "used for a different request; CONCURRENT_MODIFICATION - a concurrent daily "
							+ "servicing run kept colliding with this payment after 3 attempts (Addendum "
							+ "\u00a75); refresh and retry")
	})
	public ResponseEntity<ApiResponse<PaymentResponse>> create(
			@Parameter(description = "Endpoint-scoped retry key (TS §2.2/§2.5)", required = true)
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@Valid @RequestBody PaymentRequest request) {
		PaymentResponse posted = payments.create(idempotencyKey, request);
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(posted));
	}
}
