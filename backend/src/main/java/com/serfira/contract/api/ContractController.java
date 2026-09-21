package com.serfira.contract.api;

import com.serfira.contract.application.ContractCommandService;
import com.serfira.contract.application.ContractQueryService;
import com.serfira.contract.domain.ContractStatus;
import com.serfira.shared.api.ApiResponse;
import com.serfira.shared.api.PageResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * Contract HTTP surface (PRD C-1/C-2/C-4, DM §2). HTTP concerns only — every rule lives in the
 * application/domain layers, and every response travels in the standard {@code {data, error}}
 * envelope (TS §2.2).
 *
 * <p>Authorization today is "authenticated" (default-deny in
 * {@code ResourceServerSecurityConfiguration}); the endpoint-to-role matrix arrives with story F3
 * (Sprint 6b).
 */
@RestController
@RequestMapping("/api/v1/contracts")
@Tag(name = "Contracts", description = "Contract drafting, activation and servicing reads")
public class ContractController {

	private final ContractCommandService commands;
	private final ContractQueryService queries;

	public ContractController(ContractCommandService commands, ContractQueryService queries) {
		this.commands = commands;
		this.queries = queries;
	}

	@PostMapping
	@Operation(summary = "Create a DRAFT contract",
			description = """
					Creates a contract in DRAFT. No schedule is generated yet — activation does that (PRD C-2).
					`principal` is computed server-side as `asset_price - down_payment` and is not accepted from the client.
					An existing customer is reused when both NIK and phone match; a NIK registered with another phone,
					or a phone owned by another customer, is rejected with 409.
					The financed asset is resolved by `serial_no` (then `plate_no`) and reused; a customer may not hold two
					live contracts for the same asset (409 DUPLICATE_CONTRACT).
					Repeating the request with the same `Idempotency-Key` returns the original contract instead of creating a second one.
					Customer PII is returned masked.""")
	@ApiResponses({
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201",
					description = "Contract created (or the original one, replayed for an identical retry)"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
					description = "VALIDATION_ERROR - invalid payload, missing Idempotency-Key"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
					description = "UNAUTHORIZED — missing or invalid bearer token"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
					description = "DUPLICATE_CONTRACT / CONFLICT — live contract for the same asset, "
							+ "customer identity conflict, or a reused Idempotency-Key with a different payload")
	})
	public ResponseEntity<ApiResponse<ContractResponse>> create(
			@Parameter(description = "Endpoint-scoped retry key (TS §2.2/§2.5)", required = true)
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@Valid @RequestBody CreateContractRequest request) {
		ContractResponse created = commands.create(idempotencyKey, request);
		URI location = ServletUriComponentsBuilder.fromCurrentRequest()
				.path("/{id}")
				.buildAndExpand(created.id())
				.toUri();
		return ResponseEntity.created(location).body(ApiResponse.ok(created));
	}

	@PostMapping("/{id}/activate")
	@Operation(summary = "Activate a DRAFT contract and generate its schedule",
			description = """
					Transitions DRAFT -> ACTIVE and persists the repayment schedule in one transaction (PRD C-2).
					The body is optional: with no body, or with `start_date` null, the effective start date is the
					contract's `planned_start_date`. An explicit `start_date` overrides it (real disbursement date).
					Activation is idempotent by state: repeating it on an ACTIVE contract returns the current
					representation without writing anything; a conflicting `start_date` on an already ACTIVE contract,
					or a CLOSED/TERMINATED contract, is 409 CONTRACT_STATE_INVALID.
					The schedule uses the configuration in force on the activation date.""")
	@ApiResponses({
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
					description = "Contract activated, or already active (idempotent repeat)"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
					description = "UNAUTHORIZED - missing or invalid bearer token"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
					description = "CONTRACT_NOT_FOUND - no such contract"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
					description = "CONTRACT_STATE_INVALID - already active with a different start date, "
							+ "closed/terminated, or no start date could be resolved")
	})
	public ApiResponse<ContractResponse> activate(
			@PathVariable UUID id,
			@RequestBody(required = false) ActivateContractRequest request) {
		return ApiResponse.ok(commands.activate(id, request == null ? null : request.startDate()));
	}

	@GetMapping
	@Operation(summary = "List contracts",
			description = """
					Paged list (PRD C-4): optional `status` filter and `q` free-text filter over contract number or
					customer name. `outstanding` is null for DRAFT contracts (no schedule yet).
					Customer PII is returned masked.""")
	@ApiResponses({
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
					description = "One page of contracts"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
					description = "VALIDATION_ERROR - unknown sort property, bad direction, or paging out of range"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
					description = "UNAUTHORIZED - missing or invalid bearer token")
	})
	public ApiResponse<PageResponse<ContractListItem>> list(
			@Parameter(description = "Filter by contract status")
			@RequestParam(required = false) ContractStatus status,
			@Parameter(description = "Free text over contract_no or customer name")
			@RequestParam(name = "q", required = false) String query,
			@Parameter(description = "0-based page index")
			@RequestParam(defaultValue = "0") int page,
			@Parameter(description = "Page size (1..100)")
			@RequestParam(defaultValue = "20") int size,
			@Parameter(description = "Sort token: created_at | contract_no | status, "
					+ "optionally with ,asc|,desc (default created_at,desc)")
			@RequestParam(required = false) String sort) {
		return ApiResponse.ok(queries.list(status, query, page, size, sort));
	}

	@GetMapping("/{id}")
	@Operation(summary = "Contract detail",
			description = "Detail plus outstanding (principal residual + recognized interest residual + effective "
					+ "penalty residual). `outstanding` is null while the contract is DRAFT. PII is masked.")
	@ApiResponses({
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "The contract"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
					description = "UNAUTHORIZED - missing or invalid bearer token"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
					description = "CONTRACT_NOT_FOUND - no such contract")
	})
	public ApiResponse<ContractResponse> detail(@PathVariable UUID id) {
		return ApiResponse.ok(queries.detail(id));
	}

	@GetMapping("/{id}/installments")
	@Operation(summary = "Contract schedule",
			description = "Installments ordered by period. Empty for a DRAFT contract (the schedule is generated at "
					+ "activation). Each row reports paid/settled/written-off separately plus its outstanding.")
	@ApiResponses({
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
					description = "The schedule, oldest period first"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
					description = "UNAUTHORIZED - missing or invalid bearer token"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
					description = "CONTRACT_NOT_FOUND - no such contract")
	})
	public ApiResponse<List<InstallmentResponse>> installments(@PathVariable UUID id) {
		return ApiResponse.ok(queries.installments(id));
	}
}