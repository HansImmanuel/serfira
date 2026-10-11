package com.serfira.settlement.application;

import com.serfira.contract.application.ConsumedCredit;
import com.serfira.contract.application.ContractCreditPort;
import com.serfira.contract.application.InstallmentBillingPort;
import com.serfira.contract.application.SettlementCloseCommand;
import com.serfira.contract.application.SettlementClosePort;
import com.serfira.contract.application.SettlementContractSnapshot;
import com.serfira.contract.application.SettlementReceivablePort;
import com.serfira.ledger.application.LedgerPostingService;
import com.serfira.ledger.domain.LedgerAccount;
import com.serfira.ledger.domain.LedgerPosting;
import com.serfira.ledger.domain.LedgerPostingLine;
import com.serfira.ledger.domain.LedgerRefType;
import com.serfira.penalty.application.EffectivePenaltyPort;
import com.serfira.penalty.application.EffectivePenaltySnapshot;
import com.serfira.penalty.application.InstallmentEffectivePenalty;
import com.serfira.penalty.application.PenaltyAccrualPort;
import com.serfira.settlement.api.SettlementExecutionRequest;
import com.serfira.settlement.api.SettlementExecutionResponse;
import com.serfira.settlement.domain.Settlement;
import com.serfira.settlement.domain.SettlementAllocation;
import com.serfira.settlement.domain.SettlementAllocationType;
import com.serfira.settlement.domain.SettlementCreditApplication;
import com.serfira.settlement.domain.SettlementInstallmentInput;
import com.serfira.settlement.domain.SettlementQuote;
import com.serfira.settlement.domain.SettlementQuoteComponents;
import com.serfira.settlement.domain.SettlementQuoteEngine;
import com.serfira.settlement.domain.SettlementQuoteInput;
import com.serfira.settlement.domain.SettlementQuoteStatus;
import com.serfira.settlement.domain.SettlementResolutionEngine;
import com.serfira.settlement.domain.SettlementResolutionLine;
import com.serfira.settlement.infrastructure.SettlementAllocationRepository;
import com.serfira.settlement.infrastructure.SettlementCreditApplicationRepository;
import com.serfira.settlement.infrastructure.SettlementRepository;
import com.serfira.settlement.infrastructure.SettlementQuoteRepository;
import com.serfira.shared.clock.Clock;
import com.serfira.shared.config.SystemParameterService;
import com.serfira.shared.document.DocumentNumberGenerator;
import com.serfira.shared.document.DocumentType;
import com.serfira.shared.error.CreditExceedsSettlementException;
import com.serfira.shared.error.IdempotencyKeyExpiredException;
import com.serfira.shared.error.SettlementQuoteAlreadyExecutedException;
import com.serfira.shared.error.SettlementQuoteExpiredException;
import com.serfira.shared.error.StaleSettlementQuoteException;
import com.serfira.shared.idempotency.CanonicalRequestJson;
import com.serfira.shared.idempotency.IdempotencyService;
import com.serfira.shared.idempotency.IdempotentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The settlement-execution use case (E2, task T13, ADR-018 D1/D4/D5/D9/D10): execute a QUOTED quote into an
 * immutable {@code settlement}, resolve the contract's recognized receivable, consume all available credit,
 * post the one balanced SETTLEMENT journal entry, and close the contract — idempotently over HTTP.
 *
 * <p><b>One transaction per use case</b> (TS §2.3): the settlement row, its allocations, the consumed-credit
 * records, the contract closure and the journal entry either all commit or none do. The idempotency claim is
 * taken inside the same transaction ({@code IdempotencyService} is {@code Propagation.MANDATORY}), so a
 * failed attempt leaves no consumed key and a retry can succeed (ADR-017).
 *
 * <p><b>Re-price at execution</b> (ADR-018 D9): the service repeats accrue-before-resolve (bill due
 * interest, then accrue due penalty on one captured business date, exactly like the quote path and the
 * payment path) and re-prices the components with the same pure {@link SettlementQuoteEngine}. It compares
 * every recomputed component <b>and</b> the live {@code contract.version} to the stored quote snapshot; any
 * divergence → {@code STALE_SETTLEMENT_QUOTE}. An expired TTL → {@code SETTLEMENT_QUOTE_EXPIRED}; an
 * already-executed quote → {@code SETTLEMENT_QUOTE_ALREADY_EXECUTED}. A rejected execution writes nothing.
 *
 * <p><b>Module boundaries</b> (02_TECH_SPEC.md §1): this service owns {@code settlement},
 * {@code settlement_allocation} and {@code settlement_credit_application}. It bills and accrues through the
 * {@code contract}/{@code penalty} ports, reads the settlement snapshot and effective penalty through their
 * ports, consumes credit and closes the contract through {@code contract}-owned ports
 * ({@link ContractCreditPort}, {@link SettlementClosePort}), and posts the journal through the
 * {@code ledger} module. It never touches another module's entities or tables.
 */
@Service
public class SettlementExecutionCommandService {

	private static final Logger LOGGER = LoggerFactory.getLogger(SettlementExecutionCommandService.class);

	/** Idempotency scope of the settlement-execution endpoint (TS §2.5: keys are endpoint-scoped). */
	static final String EXECUTE_ENDPOINT = "POST /api/v1/settlements";

	/**
	 * Permanent backstop that enforces one settlement per idempotency key even if the
	 * {@code idempotency_keys} row is gone ({@code uq_settlement_idempotency}, V1). A hit means the key was
	 * already spent, so it maps to an expired-key rejection, not a retryable conflict (ADR-017).
	 */
	static final String SETTLEMENT_IDEMPOTENCY_CONSTRAINT = "uq_settlement_idempotency";

	private static final String ADMIN_FEE_KEY = "SETTLEMENT_ADMIN_FEE";
	private static final String REBATE_RATE_KEY = "SETTLEMENT_REBATE_RATE";

	private final SettlementQuoteRepository quotes;
	private final SettlementRepository settlements;
	private final SettlementAllocationRepository allocations;
	private final SettlementCreditApplicationRepository creditApplications;
	private final InstallmentBillingPort billing;
	private final PenaltyAccrualPort penalty;
	private final SettlementReceivablePort receivables;
	private final EffectivePenaltyPort effectivePenalty;
	private final ContractCreditPort contractCredit;
	private final SettlementClosePort settlementClose;
	private final DocumentNumberGenerator documentNumbers;
	private final IdempotencyService idempotency;
	private final LedgerPostingService ledger;
	private final SystemParameterService systemParameters;
	private final Clock clock;

	public SettlementExecutionCommandService(SettlementQuoteRepository quotes, SettlementRepository settlements,
			SettlementAllocationRepository allocations, SettlementCreditApplicationRepository creditApplications,
			InstallmentBillingPort billing, PenaltyAccrualPort penalty, SettlementReceivablePort receivables,
			EffectivePenaltyPort effectivePenalty, ContractCreditPort contractCredit,
			SettlementClosePort settlementClose, DocumentNumberGenerator documentNumbers,
			IdempotencyService idempotency, LedgerPostingService ledger, SystemParameterService systemParameters,
			Clock clock) {
		this.quotes = quotes;
		this.settlements = settlements;
		this.allocations = allocations;
		this.creditApplications = creditApplications;
		this.billing = billing;
		this.penalty = penalty;
		this.receivables = receivables;
		this.effectivePenalty = effectivePenalty;
		this.contractCredit = contractCredit;
		this.settlementClose = settlementClose;
		this.documentNumbers = documentNumbers;
		this.idempotency = idempotency;
		this.ledger = ledger;
		this.systemParameters = systemParameters;
		this.clock = clock;
	}

	/**
	 * Executes a settlement quote. An identical retry (same {@code Idempotency-Key} and body) replays the
	 * stored response; the same key with a different body is 409; a spent key is 409 IDEMPOTENCY_KEY_EXPIRED.
	 *
	 * @param idempotencyKey the client's {@code Idempotency-Key}
	 * @param request        the quote to execute
	 */
	@Transactional
	public SettlementExecutionResponse execute(String idempotencyKey, SettlementExecutionRequest request) {
		Objects.requireNonNull(request, "request");
		if (request.quoteId() == null) {
			throw new com.serfira.shared.error.BadRequestException("quote_id is required");
		}
		String key = idempotencyKey == null ? null : idempotencyKey.trim();
		IdempotentResult<SettlementExecutionResponse> result = idempotency.execute(
				EXECUTE_ENDPOINT, key, canonicalize(request), SettlementExecutionResponse.class,
				() -> settle(key, request));
		return result.response();
	}

	/**
	 * The mutation itself, inside the idempotency claim and the caller's transaction (write-path order
	 * validate → compute → write aggregate → post journal → commit).
	 */
	private SettlementExecutionResponse settle(String idempotencyKey, SettlementExecutionRequest request) {
		SettlementQuote quote = quotes.findById(request.quoteId())
				.orElseThrow(() -> new com.serfira.shared.error.NotFoundException(
						"settlement quote " + request.quoteId() + " was not found"));
		if (quote.getStatus() == SettlementQuoteStatus.EXECUTED) {
			throw new SettlementQuoteAlreadyExecutedException(
					"settlement quote " + quote.getQuoteNo() + " has already been executed");
		}
		if (quote.getStatus() == SettlementQuoteStatus.EXPIRED) {
			throw new SettlementQuoteExpiredException(
					"settlement quote " + quote.getQuoteNo() + " has expired");
		}

		LocalDate businessDate = clock.today();
		OffsetDateTime executedAt = clock.now();
		if (executedAt.isAfter(quote.getValidUntil())) {
			throw new SettlementQuoteExpiredException("settlement quote " + quote.getQuoteNo()
					+ " expired at " + quote.getValidUntil());
		}

		// Accrue before resolve (ADR-013, ADR-018 D8): recognize due interest, then accrue due penalty, on
		// one captured business date, before re-pricing — so each component prices against the base actually
		// in force, exactly as the quote path and the payment path do.
		UUID contractId = quote.getContractId();
		billing.billDueInterest(contractId, businessDate);
		penalty.accrueDuePenalty(contractId, businessDate);

		SettlementContractSnapshot snapshot = receivables.loadSettlementSnapshot(contractId);
		Map<UUID, InstallmentEffectivePenalty> penaltyByInstallment =
				byInstallment(effectivePenalty.loadEffectivePenalty(contractId));
		BigDecimal availableCredit = contractCredit.availableCredit(contractId);

		BigDecimal rebateRate = systemParameters.requireDecimal(REBATE_RATE_KEY, businessDate);
		BigDecimal adminFee = systemParameters.requireDecimal(ADMIN_FEE_KEY, businessDate)
				.setScale(2, java.math.RoundingMode.HALF_EVEN);

		List<SettlementInstallmentInput> engineInstallments =
				SettlementQuoteCommandService.toEngineInstallments(snapshot, penaltyByInstallment);
		SettlementQuoteComponents components = SettlementQuoteEngine.price(new SettlementQuoteInput(businessDate,
				snapshot.contractStartDate(), rebateRate, adminFee, availableCredit, engineInstallments));

		// Revalidate against the stored snapshot (ADR-018 D9). Any component or version mismatch is stale.
		requireMatchesQuote(quote, snapshot, components, availableCredit);

		// Credit policy (ADR-018 D10): consume ALL available credit; reject if it exceeds the gross.
		BigDecimal grossAmount = components.grossAmount();
		if (availableCredit.compareTo(grossAmount) > 0) {
			throw new CreditExceedsSettlementException("contract " + snapshot.contractNo()
					+ " available credit " + availableCredit + " exceeds settlement gross " + grossAmount);
		}
		BigDecimal creditUsed = availableCredit;
		BigDecimal cashReceived = grossAmount.subtract(creditUsed);

		// Receivable resolution per installment (ADR-018 D5): oldest due first, PENALTY→INTEREST→PRINCIPAL.
		List<SettlementResolutionLine> resolution = SettlementResolutionEngine.resolve(engineInstallments);

		// Persist the settlement; a uq_settlement_idempotency hit is a spent key, a uk_settlement_quote_id
		// hit is a quote already executed by a committed settlement.
		Settlement settlement = new Settlement(documentNumbers.next(DocumentType.SETTLEMENT), contractId,
				quote.getId(), cashReceived, creditUsed, quote.getRebateAmount(), adminFee, executedAt,
				idempotencyKey);
		try {
			settlements.saveAndFlush(settlement);
		} catch (DataIntegrityViolationException ex) {
			throw translateSettlementViolation(ex, quote);
		}

		List<SettlementAllocation> savedAllocations = allocations.saveAll(resolution.stream()
				.map(line -> new SettlementAllocation(settlement.getId(), line.installmentId(), line.type(),
						line.amount()))
				.toList());
		allocations.flush();

		// Consume ALL available credit and record one settlement_credit_application per source (D10).
		List<ConsumedCredit> consumed = contractCredit.consumeAllAvailableCredit(contractId, executedAt);
		List<SettlementCreditApplication> savedCreditApplications = creditApplications.saveAll(consumed.stream()
				.map(source -> new SettlementCreditApplication(settlement.getId(), source.creditId(),
						source.amount()))
				.toList());
		creditApplications.flush();

		// Close the contract and settle its open installments through the contract module's own path (D10):
		// the settled amount per installment is the receivable the allocations cleared on it.
		settlementClose.closeBySettlement(new SettlementCloseCommand(contractId, quote.getContractVersion(),
				settledByInstallment(resolution), executedAt));

		// Post the single balanced SETTLEMENT journal entry (ADR-018 D1/D4).
		ledger.post(LedgerPosting.of(LedgerRefType.SETTLEMENT, settlement.getId(), executedAt,
				"Settlement " + settlement.getSettlementNo() + " for contract " + snapshot.contractNo(),
				journalLines(snapshot.contractId(), cashReceived, creditUsed, components, resolution)));

		quote.markExecuted();
		quotes.save(quote);

		LOGGER.info("Executed settlement {} (id={}, contract={}, gross={}, cash={}, credit={})",
				settlement.getSettlementNo(), settlement.getId(), snapshot.contractNo(), grossAmount, cashReceived,
				creditUsed);
		return SettlementExecutionResponse.from(settlement, savedAllocations, savedCreditApplications);
	}

	/**
	 * ADR-018 D9: every recomputed component and the live contract version must match the stored quote; any
	 * divergence means the contract moved on since the quote was priced, so the quote is stale and the
	 * execution writes nothing.
	 */
	private static void requireMatchesQuote(SettlementQuote quote, SettlementContractSnapshot snapshot,
			SettlementQuoteComponents components, BigDecimal availableCredit) {
		if (snapshot.contractVersion() != quote.getContractVersion()) {
			throw stale(quote, "contract version", BigDecimal.valueOf(quote.getContractVersion()),
					BigDecimal.valueOf(snapshot.contractVersion()));
		}
		requireComponent(quote, "outstanding_principal", quote.getOutstandingPrincipal(),
				components.outstandingPrincipal());
		requireComponent(quote, "unpaid_billed_interest", quote.getUnpaidBilledInterest(),
				components.unpaidBilledInterest());
		requireComponent(quote, "accrued_interest", quote.getAccruedInterest(), components.accruedInterest());
		requireComponent(quote, "penalty_outstanding", quote.getPenaltyOutstanding(),
				components.penaltyOutstanding());
		requireComponent(quote, "rebate_amount", quote.getRebateAmount(), components.rebateAmount());
		requireComponent(quote, "admin_fee", quote.getAdminFee(), components.adminFee());
		requireComponent(quote, "available_credit", quote.getAvailableCredit(), availableCredit);
		requireComponent(quote, "gross_amount", quote.getGrossAmount(), components.grossAmount());
	}

	private static void requireComponent(SettlementQuote quote, String name, BigDecimal expected,
			BigDecimal recomputed) {
		if (expected.compareTo(recomputed) != 0) {
			throw stale(quote, name, expected, recomputed);
		}
	}

	private static StaleSettlementQuoteException stale(SettlementQuote quote, String name, BigDecimal expected,
			BigDecimal recomputed) {
		return new StaleSettlementQuoteException("settlement quote " + quote.getQuoteNo() + " is stale: " + name
				+ " was " + expected + " but is now " + recomputed);
	}

	/**
	 * The single SETTLEMENT journal entry (ADR-018 D4): Dr KAS (cash) + TITIPAN_NASABAH (credit); Cr the
	 * receivable accounts for the resolved components, PENDAPATAN_BUNGA for accrued + net future interest,
	 * and PENDAPATAN_ADMIN for the admin fee. A zero component contributes no line, so the entry still
	 * balances ({@code cash + credit = gross}). No DISKON_PELUNASAN line in the normal path (D3). Every line
	 * carries the contract.
	 */
	private static List<LedgerPostingLine> journalLines(UUID contractId, BigDecimal cashReceived,
			BigDecimal creditUsed, SettlementQuoteComponents components, List<SettlementResolutionLine> resolution) {
		Map<SettlementAllocationType, BigDecimal> resolvedByType = sumByType(resolution);
		BigDecimal interestIncome = components.accruedInterest().add(components.futureInterestCharged());

		List<LedgerPostingLine> lines = new ArrayList<>();
		addDebit(lines, LedgerAccount.KAS, cashReceived, contractId);
		addDebit(lines, LedgerAccount.TITIPAN_NASABAH, creditUsed, contractId);
		addCredit(lines, LedgerAccount.PIUTANG_POKOK, resolvedByType.get(SettlementAllocationType.PRINCIPAL),
				contractId);
		addCredit(lines, LedgerAccount.PIUTANG_BUNGA, resolvedByType.get(SettlementAllocationType.INTEREST),
				contractId);
		addCredit(lines, LedgerAccount.PIUTANG_DENDA, resolvedByType.get(SettlementAllocationType.PENALTY),
				contractId);
		addCredit(lines, LedgerAccount.PENDAPATAN_BUNGA, interestIncome, contractId);
		addCredit(lines, LedgerAccount.PENDAPATAN_ADMIN, components.adminFee(), contractId);
		return lines;
	}

	private static void addDebit(List<LedgerPostingLine> lines, LedgerAccount account, BigDecimal amount,
			UUID contractId) {
		if (amount != null && amount.signum() > 0) {
			lines.add(LedgerPostingLine.debit(account, amount, contractId));
		}
	}

	private static void addCredit(List<LedgerPostingLine> lines, LedgerAccount account, BigDecimal amount,
			UUID contractId) {
		if (amount != null && amount.signum() > 0) {
			lines.add(LedgerPostingLine.credit(account, amount, contractId));
		}
	}

	private static Map<SettlementAllocationType, BigDecimal> sumByType(List<SettlementResolutionLine> resolution) {
		Map<SettlementAllocationType, BigDecimal> totals = new EnumMap<>(SettlementAllocationType.class);
		for (SettlementResolutionLine line : resolution) {
			totals.merge(line.type(), line.amount(), BigDecimal::add);
		}
		return totals;
	}

	private static Map<UUID, BigDecimal> settledByInstallment(List<SettlementResolutionLine> resolution) {
		Map<UUID, BigDecimal> settled = new LinkedHashMap<>();
		for (SettlementResolutionLine line : resolution) {
			settled.merge(line.installmentId(), line.amount(), BigDecimal::add);
		}
		return settled;
	}

	private static Map<UUID, InstallmentEffectivePenalty> byInstallment(EffectivePenaltySnapshot snapshot) {
		Map<UUID, InstallmentEffectivePenalty> byId = new HashMap<>();
		for (InstallmentEffectivePenalty installment : snapshot.installments()) {
			byId.put(installment.installmentId(), installment);
		}
		return byId;
	}

	/**
	 * Translates the two settlement-write violations a client can act on. A
	 * {@code uq_settlement_idempotency} hit means the key already produced a settlement whose
	 * {@code idempotency_keys} row has since been cleaned up, so the key is spent (expired, ADR-017). A
	 * {@code uk_settlement_quote_id} hit means another committed settlement already executed this quote, so
	 * it is an already-executed conflict. Anything else keeps its original type.
	 */
	private static RuntimeException translateSettlementViolation(DataIntegrityViolationException ex,
			SettlementQuote quote) {
		Throwable cause = ex.getMostSpecificCause();
		String message = cause == null ? null : cause.getMessage();
		if (message != null && message.contains(SETTLEMENT_IDEMPOTENCY_CONSTRAINT)) {
			LOGGER.warn("settlement execution rejected by {}", SETTLEMENT_IDEMPOTENCY_CONSTRAINT);
			return new IdempotencyKeyExpiredException(
					"Idempotency-Key was already used for a settlement request");
		}
		if (message != null && message.contains("uk_settlement_quote_id")) {
			LOGGER.warn("settlement execution rejected by uk_settlement_quote_id");
			return new SettlementQuoteAlreadyExecutedException(
					"settlement quote " + quote.getQuoteNo() + " has already been executed");
		}
		return ex;
	}

	/**
	 * Canonical request JSON for the retry fingerprint (TS §2.5): the quote id is the only field that
	 * defines which settlement this is.
	 */
	private static String canonicalize(SettlementExecutionRequest request) {
		Map<String, String> fields = CanonicalRequestJson.fields();
		fields.put("quote_id", request.quoteId().toString());
		return CanonicalRequestJson.render(fields);
	}
}
