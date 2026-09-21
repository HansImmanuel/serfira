package com.serfira.contract.application;

import com.serfira.contract.api.ContractResponse;
import com.serfira.contract.api.CreateAssetRequest;
import com.serfira.contract.api.CreateContractRequest;
import com.serfira.contract.api.CreateCustomerRequest;
import com.serfira.contract.domain.Asset;
import com.serfira.contract.domain.Contract;
import com.serfira.contract.domain.ContractStateException;
import com.serfira.contract.domain.ContractStatus;
import com.serfira.contract.domain.Customer;
import com.serfira.contract.domain.Installment;
import com.serfira.contract.domain.InterestScheme;
import com.serfira.contract.domain.schedule.ScheduleEngine;
import com.serfira.contract.domain.schedule.ScheduleLine;
import com.serfira.contract.infrastructure.AssetRepository;
import com.serfira.contract.infrastructure.ContractInstallmentTotals;
import com.serfira.contract.infrastructure.ContractRepository;
import com.serfira.contract.infrastructure.CustomerRepository;
import com.serfira.contract.infrastructure.InstallmentRepository;
import com.serfira.ledger.application.LedgerPostingService;
import com.serfira.ledger.domain.LedgerAccount;
import com.serfira.ledger.domain.LedgerPosting;
import com.serfira.ledger.domain.LedgerPostingLine;
import com.serfira.ledger.domain.LedgerRefType;
import com.serfira.shared.clock.Clock;
import com.serfira.shared.config.SystemParameterKeys;
import com.serfira.shared.config.SystemParameterService;
import com.serfira.shared.document.DocumentNumberGenerator;
import com.serfira.shared.document.DocumentType;
import com.serfira.shared.error.BadRequestException;
import com.serfira.shared.error.ConflictException;
import com.serfira.shared.idempotency.CanonicalRequestJson;
import com.serfira.shared.idempotency.IdempotencyService;
import com.serfira.shared.idempotency.IdempotentResult;
import com.serfira.shared.security.PiiHasher;
import com.serfira.shared.security.PiiValidator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Contract use cases that mutate state: create a DRAFT (PRD C-1) and activate it, generating the
 * schedule exactly once (PRD C-2). One transaction per use case.
 *
 * <p>Activation is also the point where money movement starts: it posts the disbursement journal through
 * the {@code ledger} module's application service in the same transaction (TS §3, ADR-008).
 */
@Service
public class ContractCommandService {

	private static final Logger LOGGER = LoggerFactory.getLogger(ContractCommandService.class);

	/** Idempotency scope of create (TS §2.2: keys are endpoint-scoped). */
	static final String CREATE_ENDPOINT = "POST /api/v1/contracts";

	private static final int MONEY_SCALE = 2;
	private static final int RATE_SCALE = 4;
	private static final int MAX_TENOR_MONTHS = 120;

	/** Statuses that make a contract "live": it holds its asset (invariant 18). */
	private static final List<ContractStatus> LIVE_STATUSES = List.of(ContractStatus.DRAFT, ContractStatus.ACTIVE);

	private static final String LIVE_ASSET_CONSTRAINT = "uq_contract_live_asset";
	private static final String IDEMPOTENCY_CONSTRAINT = "uq_contract_idempotency";

	/**
	 * The schedule engine is a stateless pure calculator (Sprint 1) — keeping it a constant makes that
	 * purity explicit and avoids a bean that would only hold stateless behaviour.
	 */
	private static final ScheduleEngine SCHEDULE_ENGINE = new ScheduleEngine();

	private final ContractRepository contracts;
	private final CustomerRepository customers;
	private final AssetRepository assets;
	private final InstallmentRepository installments;
	private final DocumentNumberGenerator documentNumbers;
	private final SystemParameterService systemParameters;
	private final IdempotencyService idempotency;
	private final LedgerPostingService ledger;
	private final Clock clock;

	public ContractCommandService(ContractRepository contracts, CustomerRepository customers, AssetRepository assets,
			InstallmentRepository installments, DocumentNumberGenerator documentNumbers,
			SystemParameterService systemParameters, IdempotencyService idempotency,
			LedgerPostingService ledger, Clock clock) {
		this.contracts = contracts;
		this.customers = customers;
		this.assets = assets;
		this.installments = installments;
		this.documentNumbers = documentNumbers;
		this.systemParameters = systemParameters;
		this.idempotency = idempotency;
		this.ledger = ledger;
		this.clock = clock;
	}

	/**
	 * Creates a DRAFT contract. Requires the client's {@code Idempotency-Key}: an identical retry
	 * replays the stored response (same contract, same number) instead of creating a second contract
	 * (TS §2.5).
	 *
	 * <p>No installment is written here — the schedule is generated at activation (PRD C-2).
	 */
	@Transactional
	public ContractResponse create(String idempotencyKey, CreateContractRequest request) {
		if (request == null || request.customer() == null || request.asset() == null) {
			throw new BadRequestException("customer and asset are required");
		}
		String key = idempotencyKey == null ? null : idempotencyKey.trim();
		IdempotentResult<ContractResponse> result = idempotency.execute(
				CREATE_ENDPOINT, key, canonicalize(request), ContractResponse.class,
				() -> persistDraftContract(key, request));
		return result.response();
	}

	/**
	 * Activates a DRAFT contract and persists its schedule in the same transaction.
	 *
	 * <p>Idempotent by state rather than by key (TS §2.2 does not put activation in the mandatory
	 * Idempotency-Key set): DRAFT → ACTIVE generates the schedule once; a repeat on an already ACTIVE
	 * contract returns the current representation without writing anything; ACTIVE with a different
	 * start date, or a CLOSED/TERMINATED contract, is 409 {@code CONTRACT_STATE_INVALID}. The
	 * {@code (contract_id, period_no)} unique constraint and the {@code @Version} lock keep a
	 * concurrent double activation from writing two schedules.
	 *
	 * <p>On the DRAFT → ACTIVE transition it also posts the disbursement entry
	 * ({@code PIUTANG_POKOK} debit / {@code KAS} credit, TS §3) for the principal, so the receivable that
	 * the schedule represents exists in the ledger from the first moment it is collectible (ADR-008).
	 *
	 * @param requestedStartDate optional override; {@code null} means "use planned_start_date"
	 */
	@Transactional
	public ContractResponse activate(UUID contractId, LocalDate requestedStartDate) {
		Contract contract = contracts.findDetailById(contractId)
				.orElseThrow(() -> new ContractNotFoundException(contractId));

		if (contract.isActive()) {
			if (requestedStartDate != null && !requestedStartDate.equals(contract.getStartDate())) {
				throw new ContractStateException("contract " + contract.getContractNo()
						+ " is already active with start date " + contract.getStartDate());
			}
			return ContractResponse.from(contract, outstandingOf(contractId));
		}

		LocalDate effectiveStartDate = requestedStartDate != null ? requestedStartDate : contract.getPlannedStartDate();
		// Re-snapshot the configuration here: what matters is the configuration in force when the
		// contract went live (DM §1.3), not when the draft was authored.
		int gracePeriodDays = systemParameters.requireInt(SystemParameterKeys.DEFAULT_GRACE_PERIOD_DAYS);
		BigDecimal penaltyRateDaily = systemParameters.requireDecimal(SystemParameterKeys.DEFAULT_PENALTY_RATE_DAILY);

		contract.activate(effectiveStartDate, gracePeriodDays, penaltyRateDaily);
		// Flush the transition (and its version bump) before inserting children, so a concurrent
		// activation loses on the contract version rather than on the installment unique constraint.
		contracts.saveAndFlush(contract);

		List<Installment> schedule = SCHEDULE_ENGINE
				.generate(contract.getPrincipal(), contract.getInterestRate(), effectiveStartDate,
						contract.getTenorMonths(), contract.getInterestScheme())
				.stream()
				.map(line -> toInstallment(contract, line))
				.toList();
		installments.saveAll(schedule);
		installments.flush();

		// Disbursement journal (TS §3 "Aktivasi kontrak (disburse)"): activation creates the receivable and
		// pays the cash out, so the money movement is recorded in this same transaction (ADR-008). The
		// idempotent repeat above returns before this point, so an already ACTIVE contract never double-posts.
		ledger.post(LedgerPosting.of(LedgerRefType.CONTRACT_ACTIVATION, contractId,
				activationEntryDate(effectiveStartDate), "Disbursement of " + contract.getContractNo(), List.of(
						LedgerPostingLine.debit(LedgerAccount.PIUTANG_POKOK, contract.getPrincipal(), contractId),
						LedgerPostingLine.credit(LedgerAccount.KAS, contract.getPrincipal(), contractId))));

		LOGGER.info("Activated contract {} (id={}, installments={})",
				contract.getContractNo(), contractId, schedule.size());
		return ContractResponse.from(contract, outstandingOf(contractId));
	}

	/**
	 * Business date of the activation entry: the effective start date at the start of the Serfira business
	 * day (Asia/Jakarta), so the accounting date is the contractual date instead of the wall-clock time of
	 * the click (ADR-008). {@code posted_at} still comes from the application clock inside the posting
	 * service.
	 */
	private OffsetDateTime activationEntryDate(LocalDate startDate) {
		return startDate.atStartOfDay(clock.zone()).toOffsetDateTime();
	}

	private ContractResponse persistDraftContract(String idempotencyKey, CreateContractRequest request) {
		// PII format validation happens before any Customer is constructed (review H-2).
		PiiValidator.requireValidNik(request.customer().nik());
		PiiValidator.requireValidPhone(request.customer().phone());

		ProductTerms terms = productTerms(request);
		Customer customer = resolveCustomer(request.customer());
		Asset asset = resolveAsset(request.asset());

		if (contracts.existsByCustomerIdAndAssetIdAndStatusIn(customer.getId(), asset.getId(), LIVE_STATUSES)) {
			throw new DuplicateContractException("customer " + customer.getId()
					+ " already has a live contract for the same asset");
		}

		int gracePeriodDays = systemParameters.requireInt(SystemParameterKeys.DEFAULT_GRACE_PERIOD_DAYS);
		BigDecimal penaltyRateDaily = systemParameters.requireDecimal(SystemParameterKeys.DEFAULT_PENALTY_RATE_DAILY);
		String contractNo = documentNumbers.next(DocumentType.CONTRACT);

		Contract contract = new Contract(contractNo, customer, asset, terms.assetPrice(), terms.principal(),
				terms.downPayment(), terms.tenorMonths(), terms.interestScheme(), terms.interestRate(),
				gracePeriodDays, penaltyRateDaily, terms.plannedStartDate(), idempotencyKey);
		try {
			contracts.saveAndFlush(contract);
		} catch (DataIntegrityViolationException ex) {
			throw translateCreateViolation(ex);
		}

		LOGGER.info("Created contract {} (id={}, status=DRAFT)", contractNo, contract.getId());
		return ContractResponse.from(contract, null);
	}

	/**
	 * Reuses the customer identified by {@code nik_hash} when the phone matches, and refuses to guess
	 * otherwise: a NIK registered with another phone, or a phone owned by another customer, is 409.
	 * An existing customer row is never mutated — there is no customer-update story (PRD C-5 is
	 * deferred), so silently overwriting PII would destroy the audit trail.
	 */
	private Customer resolveCustomer(CreateCustomerRequest request) {
		String rawNik = request.nik().trim();
		String rawPhone = request.phone().trim();
		String nikHash = PiiHasher.hashNik(rawNik);
		String phoneLookup = PiiHasher.hashPhone(rawPhone);

		Optional<Customer> byNik = customers.findByNikHash(nikHash);
		if (byNik.isPresent()) {
			Customer existing = byNik.get();
			if (!existing.getPhoneLookup().equals(phoneLookup)) {
				throw new ConflictException("NIK is already registered with a different phone number");
			}
			return existing;
		}
		if (customers.findByPhoneLookup(phoneLookup).isPresent()) {
			throw new ConflictException("Phone number is already registered to a different customer");
		}
		// The validated raw value is stored; equivalence/uniqueness lives in the HMAC lookup columns
		// (ADR-004), so the ciphertext column never needs to be queried.
		return customers.saveAndFlush(new Customer(request.fullName().trim(), rawNik, nikHash, rawPhone,
				phoneLookup, trimToNull(request.address())));
	}

	/**
	 * Resolves the financed item by identity — {@code serial_no} first, then {@code plate_no} — and
	 * reuses that row (invariant 18). An asset with neither identifier always inserts a new row; such
	 * assets cannot be de-duplicated by identity, so only the idempotency key protects them.
	 * An existing row is reused <b>unchanged</b>: the request's brand/model are not applied to it.
	 */
	private Asset resolveAsset(CreateAssetRequest request) {
		String serialNo = trimToNull(request.serialNo());
		String plateNo = trimToNull(request.plateNo());
		Optional<Asset> bySerial = serialNo == null ? Optional.empty() : assets.findBySerialNo(serialNo);
		Optional<Asset> byPlate = plateNo == null ? Optional.empty() : assets.findByPlateNo(plateNo);
		if (bySerial.isPresent() && byPlate.isPresent() && !bySerial.get().getId().equals(byPlate.get().getId())) {
			throw new ConflictException("serial number and plate number belong to different assets");
		}
		if (bySerial.isPresent()) {
			return bySerial.get();
		}
		if (byPlate.isPresent()) {
			return byPlate.get();
		}
		try {
			return assets.saveAndFlush(new Asset(request.assetType(), request.brand().trim(),
					request.model().trim(), serialNo, plateNo));
		} catch (DataIntegrityViolationException ex) {
			throw new ConflictException("asset serial number or plate number is already registered");
		}
	}

	/**
	 * Validates and normalizes the commercial terms (story B5 decision D8): money at scale ≤ 2, rate
	 * at scale ≤ 4 within {@code 0..1}, {@code 1 <= tenor <= 120},
	 * {@code 0 <= down_payment < asset_price}, and {@code principal = asset_price − down_payment}
	 * computed server-side (never accepted from the client). The DB CHECKs remain the backstop.
	 */
	private static ProductTerms productTerms(CreateContractRequest request) {
		if (request.assetPrice() == null || request.downPayment() == null || request.interestRate() == null
				|| request.interestScheme() == null || request.plannedStartDate() == null) {
			throw new BadRequestException("asset_price, down_payment, interest_scheme, interest_rate and "
					+ "planned_start_date are required");
		}
		if (request.tenorMonths() < 1 || request.tenorMonths() > MAX_TENOR_MONTHS) {
			throw new BadRequestException("tenor_months must be between 1 and " + MAX_TENOR_MONTHS);
		}
		BigDecimal assetPrice = requireScale(request.assetPrice(), MONEY_SCALE, "asset_price");
		BigDecimal downPayment = requireScale(request.downPayment(), MONEY_SCALE, "down_payment");
		if (assetPrice.signum() <= 0) {
			throw new BadRequestException("asset_price must be greater than 0");
		}
		if (downPayment.signum() < 0) {
			throw new BadRequestException("down_payment must be >= 0");
		}
		if (downPayment.compareTo(assetPrice) >= 0) {
			throw new BadRequestException("down_payment must be less than asset_price");
		}
		BigDecimal interestRate = requireScale(request.interestRate(), RATE_SCALE, "interest_rate");
		if (interestRate.signum() < 0 || interestRate.compareTo(BigDecimal.ONE) > 0) {
			throw new BadRequestException("interest_rate must be between 0.0000 and 1.0000");
		}
		return new ProductTerms(assetPrice, downPayment, assetPrice.subtract(downPayment), request.tenorMonths(),
				request.interestScheme(), interestRate, request.plannedStartDate());
	}

	/** Rejects values finer than the documented column scale instead of silently rounding them. */
	private static BigDecimal requireScale(BigDecimal value, int maxScale, String fieldName) {
		if (value.scale() > maxScale) {
			throw new BadRequestException(fieldName + " must have at most " + maxScale + " decimal places");
		}
		return value.setScale(maxScale, RoundingMode.HALF_EVEN);
	}

	/**
	 * Canonical form of the create request for the idempotency fingerprint: fields in a fixed order
	 * and money/rate normalized to their column scale, so {@code 16000000} and {@code 16000000.00} are
	 * the same request. Only the keyed digest of this payload is stored (see
	 * {@code CanonicalRequestJson}).
	 */
	private static String canonicalize(CreateContractRequest request) {
		Map<String, String> fields = CanonicalRequestJson.fields();
		fields.put("customer.full_name", CanonicalRequestJson.text(request.customer().fullName()));
		fields.put("customer.nik", CanonicalRequestJson.text(request.customer().nik()));
		fields.put("customer.phone", CanonicalRequestJson.text(request.customer().phone()));
		fields.put("customer.address", CanonicalRequestJson.text(request.customer().address()));
		fields.put("asset.asset_type", CanonicalRequestJson.name(request.asset().assetType()));
		fields.put("asset.brand", CanonicalRequestJson.text(request.asset().brand()));
		fields.put("asset.model", CanonicalRequestJson.text(request.asset().model()));
		fields.put("asset.serial_no", CanonicalRequestJson.text(request.asset().serialNo()));
		fields.put("asset.plate_no", CanonicalRequestJson.text(request.asset().plateNo()));
		fields.put("asset_price", CanonicalRequestJson.money(request.assetPrice()));
		fields.put("down_payment", CanonicalRequestJson.money(request.downPayment()));
		fields.put("tenor_months", Integer.toString(request.tenorMonths()));
		fields.put("interest_scheme", CanonicalRequestJson.name(request.interestScheme()));
		fields.put("interest_rate", CanonicalRequestJson.rate(request.interestRate()));
		fields.put("planned_start_date", CanonicalRequestJson.date(request.plannedStartDate()));
		return CanonicalRequestJson.render(fields);
	}

	/** Outstanding receivable of a contract, or {@code null} when it has no schedule yet (DRAFT). */
	private BigDecimal outstandingOf(UUID contractId) {
		return installments.sumTotalsByContractId(contractId)
				.map(ContractInstallmentTotals::outstanding)
				.orElse(null);
	}

	private static Installment toInstallment(Contract contract, ScheduleLine line) {
		return new Installment(contract, line.periodNo(), line.dueDate(), line.principalAmount(),
				line.interestAmount());
	}

	/**
	 * Translates the create-time uniqueness violations a client can act on; anything else keeps its
	 * original type and is handled centrally as 409 {@code CONFLICT}.
	 */
	private static RuntimeException translateCreateViolation(DataIntegrityViolationException ex) {
		String cause = ex.getMostSpecificCause().getMessage();
		if (cause != null && cause.contains(LIVE_ASSET_CONSTRAINT)) {
			LOGGER.warn("contract create rejected by {}", LIVE_ASSET_CONSTRAINT);
			return new DuplicateContractException("customer already has a live contract for the same asset");
		}
		if (cause != null && cause.contains(IDEMPOTENCY_CONSTRAINT)) {
			LOGGER.warn("contract create rejected by {}", IDEMPOTENCY_CONSTRAINT);
			return new ConflictException("this Idempotency-Key was already used for a contract create request");
		}
		return ex;
	}

	private static String trimToNull(String value) {
		return CanonicalRequestJson.text(value);
	}

	/** Validated, scale-normalized commercial terms plus the server-computed principal. */
	private record ProductTerms(BigDecimal assetPrice, BigDecimal downPayment, BigDecimal principal, int tenorMonths,
			InterestScheme interestScheme, BigDecimal interestRate, LocalDate plannedStartDate) {
	}
}