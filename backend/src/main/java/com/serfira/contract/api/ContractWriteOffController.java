package com.serfira.contract.api;

import com.serfira.contract.application.ContractWriteOffRetryingService;
import com.serfira.shared.api.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Contract write-off HTTP surface (F5, task T19; DM §1.3/§1.12, Addendum §13). HTTP concerns only — the
 * accrue-before-resolve, the receivable cap, the installment write-offs, the contract termination and the
 * single journal posting live in the application/domain layers, and every response travels in the standard
 * {@code {data, error}} envelope (TS §2.2).
 *
 * <p>Only ADMIN_OPERASIONAL may write a contract off (Addendum §3.4), enforced in the matcher table of
 * {@code ResourceServerSecurityConfiguration} (ADR-015). Kept on the {@code /api/v1/contracts} base path
 * next to the other contract commands.
 */
@RestController
@RequestMapping("/api/v1/contracts")
@Tag(name = "Contract write-off", description = "Receivable write-off (penghapusan piutang): terminate and cap")
public class ContractWriteOffController {

	private final ContractWriteOffRetryingService writeOffs;

	public ContractWriteOffController(ContractWriteOffRetryingService writeOffs) {
		this.writeOffs = writeOffs;
	}

	@PostMapping("/{id}/write-off")
	@Operation(summary = "Write off a contract's recognized receivable and terminate it",
			description = """
					Records an external decision to stop collecting on an ACTIVE contract (F5). In one
					transaction the server bills due interest and accrues due penalty through today
					(accrue-before-resolve, ADR-013), caps each open installment's recognized receivable
					(penalty + billed interest + principal; future unrecognized interest is never written off),
					moves those installments to WRITTEN_OFF, closes the contract ACTIVE -> TERMINATED with the
					recording actor (the authenticated user, never the body) and the reason, and posts one
					balanced WRITE_OFF journal entry (Dr BIAYA_PENGHAPUSAN_PIUTANG / Cr PIUTANG_POKOK,
					PIUTANG_BUNGA, PIUTANG_DENDA). After the write-off the contract is non-ACTIVE, so nothing
					more bills or accrues. Repeating the request with the same Idempotency-Key replays the
					stored response instead of writing off twice. Allowed role: ADMIN_OPERASIONAL.""")
	@ApiResponses({
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201",
					description = "Contract written off (or the original response, replayed for an identical retry)"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
					description = "VALIDATION_ERROR - missing/blank reason or missing Idempotency-Key"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
					description = "UNAUTHORIZED - missing or invalid bearer token (bad signature, expired, "
							+ "no exp, or a sub that is not a user id)"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
					description = "FORBIDDEN - allowed role: ADMIN_OPERASIONAL"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
					description = "CONTRACT_NOT_FOUND - no such contract"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
					description = "CONTRACT_STATE_INVALID - the contract is not ACTIVE; CONFLICT - the "
							+ "Idempotency-Key was already used for a different request; IDEMPOTENCY_KEY_EXPIRED - "
							+ "the Idempotency-Key is spent (mint a fresh key); CONCURRENT_MODIFICATION - a "
							+ "concurrent daily servicing run kept colliding after 3 attempts; refresh and retry")
	})
	public ResponseEntity<ApiResponse<WriteOffResponse>> writeOff(
			@PathVariable UUID id,
			@Parameter(description = "Endpoint-scoped retry key (TS §2.2/§2.5)", required = true)
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@Valid @RequestBody WriteOffContractRequest request) {
		WriteOffResponse response = writeOffs.writeOff(id, idempotencyKey, request);
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
	}
}
