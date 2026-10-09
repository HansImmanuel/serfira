package com.serfira.settlement.application;

import com.serfira.contract.application.ContractCreditPort;
import com.serfira.contract.application.InstallmentBillingPort;
import com.serfira.contract.application.SettlementContractSnapshot;
import com.serfira.contract.application.SettlementInstallment;
import com.serfira.contract.application.SettlementReceivablePort;
import com.serfira.penalty.application.EffectivePenaltyPort;
import com.serfira.penalty.application.InstallmentEffectivePenalty;
import com.serfira.penalty.application.PenaltyAccrualPort;
import com.serfira.penalty.application.EffectivePenaltySnapshot;
import com.serfira.settlement.api.SettlementQuoteResponse;
import com.serfira.settlement.domain.SettlementInstallmentInput;
import com.serfira.settlement.domain.SettlementQuote;
import com.serfira.settlement.domain.SettlementQuoteComponents;
import com.serfira.settlement.domain.SettlementQuoteEngine;
import com.serfira.settlement.domain.SettlementQuoteInput;
import com.serfira.settlement.infrastructure.SettlementQuoteRepository;
import com.serfira.shared.clock.Clock;
import com.serfira.shared.config.SystemParameterService;
import com.serfira.shared.document.DocumentNumberGenerator;
import com.serfira.shared.document.DocumentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The settlement-quote use case (E1, task T12, ADR-018). Prices an early settlement for an ACTIVE
 * contract and persists an immutable {@code settlement_quote}; it posts <b>no</b> journal and resolves no
 * money (execution is T13).
 *
 * <p><b>Accrue before resolve</b> (ADR-013, ADR-018 D8): in this one transaction it first bills due
 * interest and accrues due penalty through the quote business date, so each component prices against the
 * base actually in force — the settlement analogue of what the payment path does (T4). Then it reads the
 * settlement snapshot (which sees that freshly recognized state because the reads join this transaction),
 * the effective penalty (through the {@code penalty} port, invariant 9), and the available credit
 * (through the {@code contract} port), prices the quote with the pure {@link SettlementQuoteEngine}, and
 * saves it.
 *
 * <p><b>Config</b> (seeded {@code system_parameter}, Addendum §1): {@code SETTLEMENT_ADMIN_FEE},
 * {@code SETTLEMENT_REBATE_RATE}, {@code SETTLEMENT_QUOTE_TTL_MINUTES}, read in force on the quote
 * business date so a quote prices against the parameters effective that day.
 *
 * <p><b>Module boundaries</b> (02_TECH_SPEC.md §1): {@code settlement} depends only on {@code contract}
 * and {@code penalty} ports plus {@code shared}; it never touches another module's entities or tables.
 */
@Service
public class SettlementQuoteCommandService {

	private static final Logger LOGGER = LoggerFactory.getLogger(SettlementQuoteCommandService.class);

	private static final int MONEY_SCALE = 2;
	private static final BigDecimal ZERO_MONEY = BigDecimal.ZERO.setScale(MONEY_SCALE);

	private static final String ADMIN_FEE_KEY = "SETTLEMENT_ADMIN_FEE";
	private static final String REBATE_RATE_KEY = "SETTLEMENT_REBATE_RATE";
	private static final String TTL_MINUTES_KEY = "SETTLEMENT_QUOTE_TTL_MINUTES";

	private final InstallmentBillingPort billing;
	private final PenaltyAccrualPort penalty;
	private final SettlementReceivablePort receivables;
	private final EffectivePenaltyPort effectivePenalty;
	private final ContractCreditPort contractCredit;
	private final SystemParameterService systemParameters;
	private final DocumentNumberGenerator documentNumbers;
	private final SettlementQuoteRepository quotes;
	private final Clock clock;

	public SettlementQuoteCommandService(InstallmentBillingPort billing, PenaltyAccrualPort penalty,
			SettlementReceivablePort receivables, EffectivePenaltyPort effectivePenalty,
			ContractCreditPort contractCredit, SystemParameterService systemParameters,
			DocumentNumberGenerator documentNumbers, SettlementQuoteRepository quotes, Clock clock) {
		this.billing = billing;
		this.penalty = penalty;
		this.receivables = receivables;
		this.effectivePenalty = effectivePenalty;
		this.contractCredit = contractCredit;
		this.systemParameters = systemParameters;
		this.documentNumbers = documentNumbers;
		this.quotes = quotes;
		this.clock = clock;
	}

	/**
	 * Prices and persists a settlement quote.
	 *
	 * @param contractId the contract to settle
	 * @return the priced quote
	 * @throws com.serfira.contract.application.ContractNotFoundException if no contract has that id (404)
	 * @throws com.serfira.contract.domain.ContractStateException          if the contract is not ACTIVE (409)
	 */
	@Transactional
	public SettlementQuoteResponse quote(UUID contractId) {
		Objects.requireNonNull(contractId, "contractId");
		LocalDate businessDate = clock.today();

		// Accrue before resolve (ADR-013, ADR-018 D8): recognize due interest, then accrue due penalty,
		// using one captured business date, before pricing. Billing precedes penalty because the daily
		// charge is based on recognized principal and interest.
		billing.billDueInterest(contractId, businessDate);
		penalty.accrueDuePenalty(contractId, businessDate);

		SettlementContractSnapshot snapshot = receivables.loadSettlementSnapshot(contractId);
		Map<UUID, InstallmentEffectivePenalty> penaltyByInstallment =
				byInstallment(effectivePenalty.loadEffectivePenalty(contractId));
		BigDecimal availableCredit = contractCredit.availableCredit(contractId);

		BigDecimal rebateRate = systemParameters.requireDecimal(REBATE_RATE_KEY, businessDate);
		BigDecimal adminFee = money(systemParameters.requireDecimal(ADMIN_FEE_KEY, businessDate));
		int ttlMinutes = systemParameters.requireInt(TTL_MINUTES_KEY, businessDate);

		SettlementQuoteInput engineInput = new SettlementQuoteInput(businessDate, snapshot.contractStartDate(),
				rebateRate, adminFee, availableCredit, toEngineInstallments(snapshot, penaltyByInstallment));
		SettlementQuoteComponents components = SettlementQuoteEngine.price(engineInput);

		OffsetDateTime quotedAt = clock.now();
		OffsetDateTime validUntil = quotedAt.plusMinutes(ttlMinutes);
		SettlementQuote quote = new SettlementQuote(documentNumbers.next(DocumentType.QUOTE), snapshot.contractId(),
				quotedAt, validUntil, snapshot.contractVersion(), components);
		SettlementQuote saved = quotes.save(quote);

		LOGGER.info("Priced settlement quote {} (id={}, contract={}, gross={}, cash_due={}, valid_until={})",
				saved.getQuoteNo(), saved.getId(), snapshot.contractNo(), saved.getGrossAmount(), saved.getCashDue(),
				saved.getValidUntil());
		return SettlementQuoteResponse.from(saved);
	}

	/**
	 * Maps each installment's raw amounts plus its effective-penalty record into the engine input.
	 *
	 * <p>{@code resolvedBeyondPenalty} is the money already resolved against interest and principal:
	 * {@code resolvedAmount − paidPenalty}. {@code resolvedAmount} ({@code paid + settled + written-off})
	 * is money only, and the waterfall paid penalty before interest/principal, so subtracting the paid
	 * penalty leaves exactly the interest+principal share. The penalty <b>adjustment</b> (a waiver) is
	 * <b>not</b> subtracted here: a waiver lowers the recognized penalty, not any money, so it never enters
	 * {@code resolvedAmount}. Its only effect on the quote is already carried by {@code effective}
	 * ({@code max(0, gross − adjustment − paidPenalty)}); subtracting it a second time from the money-based
	 * {@code resolvedAmount} understated interest/principal and overstated {@code gross_amount}/{@code cash_due}
	 * (PR #9 review finding 1).
	 *
	 * <p>Known limitation (PR #9 review finding 2, ADR-019 follow-up): {@code paidPenalty} counts only
	 * active PENALTY {@code payment_allocation} rows, so a penalty resolved by a <b>credit application</b>
	 * (which raises {@code paid_amount} but writes no payment allocation) is not removed here and is still
	 * carried as interest/principal — the same gap by which {@code effective} overstates a credit-funded
	 * penalty. Closing it needs the credit-funded penalty exposed through the penalty/contract seam and is
	 * tracked for T13, not fixed in this quote-only change.
	 */
	static List<SettlementInstallmentInput> toEngineInstallments(SettlementContractSnapshot snapshot,
			Map<UUID, InstallmentEffectivePenalty> penaltyByInstallment) {
		List<SettlementInstallmentInput> inputs = new ArrayList<>(snapshot.installments().size());
		for (SettlementInstallment installment : snapshot.installments()) {
			InstallmentEffectivePenalty penalty = penaltyByInstallment.get(installment.installmentId());
			BigDecimal effective = penalty == null ? ZERO_MONEY : penalty.effective();
			BigDecimal paidPenalty = penalty == null ? ZERO_MONEY : penalty.paidPenalty();
			BigDecimal resolvedBeyondPenalty = max0(installment.resolvedAmount().subtract(paidPenalty));
			inputs.add(new SettlementInstallmentInput(installment.installmentId(), installment.periodNo(),
					installment.dueDate(), installment.resolvedOutsidePayment(),
					installment.principalAmount(), installment.interestAmount(),
					installment.recognizedInterestAmount(), effective, resolvedBeyondPenalty));
		}
		return inputs;
	}

	private static Map<UUID, InstallmentEffectivePenalty> byInstallment(EffectivePenaltySnapshot snapshot) {
		Map<UUID, InstallmentEffectivePenalty> byId = new HashMap<>();
		for (InstallmentEffectivePenalty installment : snapshot.installments()) {
			byId.put(installment.installmentId(), installment);
		}
		return byId;
	}

	private static BigDecimal max0(BigDecimal value) {
		return value.signum() > 0 ? value : ZERO_MONEY;
	}

	private static BigDecimal money(BigDecimal value) {
		return value.setScale(MONEY_SCALE, RoundingMode.HALF_EVEN);
	}
}
