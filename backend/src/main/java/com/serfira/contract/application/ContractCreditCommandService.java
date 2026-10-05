package com.serfira.contract.application;

import com.serfira.contract.api.CreditApplicationHistoryItem;
import com.serfira.contract.api.CreditApplicationResponse;
import com.serfira.contract.domain.Contract;
import com.serfira.contract.domain.ContractCredit;
import com.serfira.contract.domain.ContractCreditApplication;
import com.serfira.contract.domain.ContractCreditStatus;
import com.serfira.contract.domain.ContractStateException;
import com.serfira.contract.domain.Installment;
import com.serfira.contract.domain.credit.CreditAllocationInput;
import com.serfira.contract.domain.credit.CreditAllocationLine;
import com.serfira.contract.domain.credit.CreditAllocationResult;
import com.serfira.contract.domain.credit.CreditApplicationEngine;
import com.serfira.contract.domain.credit.CreditComponent;
import com.serfira.contract.infrastructure.ContractCreditApplicationRepository;
import com.serfira.contract.infrastructure.ContractCreditRepository;
import com.serfira.contract.infrastructure.ContractRepository;
import com.serfira.contract.infrastructure.CreditAppliedTotal;
import com.serfira.contract.infrastructure.InstallmentRepository;
import com.serfira.ledger.application.LedgerPostingService;
import com.serfira.ledger.domain.LedgerAccount;
import com.serfira.ledger.domain.LedgerPosting;
import com.serfira.ledger.domain.LedgerPostingLine;
import com.serfira.ledger.domain.LedgerRefType;
import com.serfira.shared.clock.Clock;
import com.serfira.shared.error.BadRequestException;
import com.serfira.shared.error.ConflictException;
import com.serfira.shared.error.CreditNotApplicableException;
import com.serfira.shared.money.DecimalBounds;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The credit-apply use case (E3, task T14): apply a contract's available customer credit to its oldest
 * recognized receivable, reusing the payment waterfall PENALTY → INTEREST → PRINCIPAL (ADR-009).
 *
 * <p><b>One transaction per use case</b> (TS §2.3): the {@code contract_credit_application} rows, the
 * credit status transitions, the installment resolution and the one {@code CREDIT_APPLICATION} journal
 * entry either all commit or none do. {@code flush} forces the deferred triggers (V14 cap and status
 * guard, V3 installment amounts, V3 journal balance) to fire inside this transaction so a broken apply
 * rolls everything back together.
 *
 * <p><b>Module boundaries</b> (02_TECH_SPEC.md §1): this service lives in {@code contract}, which owns
 * {@code contract_credit}, {@code contract_credit_application} and {@code installment}. It reads its own
 * installment columns to build the engine input (never {@code payment}'s {@code payment_allocation}),
 * resolves installments through {@link InstallmentReceivablePort#applyPaymentResolution} (same mutator as
 * a payment, which already derives PAID/PARTIALLY_PAID and the maturity close), and posts the journal
 * through the {@code ledger} module.
 *
 * <p><b>Journal ref_id</b>: one entry per apply call, keyed to the first {@code contract_credit_application}
 * id created in the call ({@code ref_type = CREDIT_APPLICATION}). {@code CREDIT_APPLICATION} is kept out
 * of {@code uq_journal_entry_event}; the {@code LedgerPostingService} service guard enforces one
 * non-reversal entry per {@code (ref_type, ref_id)}, consistent with how PAYMENT behaves (ADR-008 d5).
 * Each apply uses a fresh application id, so every call is a distinct event.
 */
@Service
public class ContractCreditCommandService {

	private static final Logger LOGGER = LoggerFactory.getLogger(ContractCreditCommandService.class);

	private static final int MONEY_SCALE = 2;
	private static final BigDecimal ZERO_MONEY = BigDecimal.ZERO.setScale(MONEY_SCALE);

	private final ContractRepository contracts;
	private final InstallmentRepository installments;
	private final ContractCreditRepository credits;
	private final ContractCreditApplicationRepository applications;
	private final InstallmentReceivablePort receivables;
	private final LedgerPostingService ledger;
	private final Clock clock;

	public ContractCreditCommandService(ContractRepository contracts, InstallmentRepository installments,
			ContractCreditRepository credits, ContractCreditApplicationRepository applications,
			InstallmentReceivablePort receivables, LedgerPostingService ledger, Clock clock) {
		this.contracts = contracts;
		this.installments = installments;
		this.credits = credits;
		this.applications = applications;
		this.receivables = receivables;
		this.ledger = ledger;
		this.clock = clock;
	}

	/**
	 * Applies available credit to the contract.
	 *
	 * @param contractId      contract whose credit to apply
	 * @param requestedAmount amount to apply, or {@code null} to apply the full available balance
	 * @return what was applied and the balance afterwards
	 * @throws ContractNotFoundException     if no contract has that id (404)
	 * @throws ContractStateException        if the contract is not ACTIVE (409)
	 * @throws BadRequestException           if a supplied amount is not positive scale-2 money (400)
	 * @throws ConflictException             if a supplied amount exceeds the available balance (409)
	 * @throws CreditNotApplicableException  if there is no available credit, or nothing it can reduce (409)
	 */
	@Transactional
	public CreditApplicationResponse apply(UUID contractId, BigDecimal requestedAmount) {
		Contract contract = contracts.findById(contractId)
				.orElseThrow(() -> new ContractNotFoundException(contractId));
		if (!contract.isActive()) {
			throw new ContractStateException("contract " + contract.getContractNo() + " is " + contract.getStatus()
					+ " and cannot have credit applied; only an ACTIVE contract can");
		}

		List<ContractCredit> availableCredits =
				credits.findByContractIdAndStatusOrderByCreatedAtAsc(contractId, ContractCreditStatus.AVAILABLE);
		Map<UUID, BigDecimal> balanceByCredit = availableBalances(availableCredits);
		BigDecimal availableBalance = sum(balanceByCredit.values());
		if (availableBalance.signum() == 0) {
			throw new CreditNotApplicableException(
					"contract " + contract.getContractNo() + " has no available credit to apply");
		}

		BigDecimal amountToApply = resolveRequestedAmount(requestedAmount, availableBalance,
				contract.getContractNo());

		List<Installment> schedule = installments.findByContractIdOrderByPeriodNo(contractId);
		CreditAllocationResult allocation = CreditApplicationEngine.allocate(amountToApply, clock.today(),
				toAllocationInputs(schedule));
		if (allocation.appliedTotal().signum() == 0) {
			throw new CreditNotApplicableException("contract " + contract.getContractNo()
					+ " has no due installment with recognized receivable to apply credit against");
		}

		OffsetDateTime appliedAt = clock.now();
		List<ContractCreditApplication> created =
				consumeCredits(availableCredits, balanceByCredit, allocation, appliedAt);
		applications.flush();

		receivables.applyPaymentResolution(contractId, resolvedByInstallment(allocation), appliedAt);

		UUID refId = created.get(0).getId();
		ledger.post(LedgerPosting.of(LedgerRefType.CREDIT_APPLICATION, refId, appliedAt,
				"Credit application for contract " + contract.getContractNo(),
				journalLines(contractId, allocation)));

		BigDecimal balanceAfter = availableBalance.subtract(allocation.appliedTotal());
		LOGGER.info("Applied credit {} to contract {} ({} application(s), balance {} -> {})",
				allocation.appliedTotal(), contract.getContractNo(), created.size(), availableBalance, balanceAfter);
		return new CreditApplicationResponse(allocation.appliedTotal(), balanceAfter, toHistory(created));
	}

	/**
	 * Validates a supplied amount or falls back to the full available balance. A supplied amount must be
	 * positive scale-2 money (400) and must not exceed the available balance (409).
	 */
	private BigDecimal resolveRequestedAmount(BigDecimal requestedAmount, BigDecimal availableBalance,
			String contractNo) {
		if (requestedAmount == null) {
			return availableBalance;
		}
		DecimalBounds.requireMoneyDomain(requestedAmount, "amount");
		if (requestedAmount.scale() > MONEY_SCALE) {
			throw new BadRequestException("amount must be money with at most " + MONEY_SCALE + " decimal places");
		}
		if (requestedAmount.signum() <= 0) {
			throw new BadRequestException("amount must be > 0");
		}
		BigDecimal normalized = requestedAmount.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
		if (normalized.compareTo(availableBalance) > 0) {
			throw new ConflictException("amount " + normalized + " exceeds the available credit balance "
					+ availableBalance + " of contract " + contractNo);
		}
		return normalized;
	}

	/** Available balance per AVAILABLE credit: {@code amount − Σ its applications}. */
	private Map<UUID, BigDecimal> availableBalances(List<ContractCredit> availableCredits) {
		if (availableCredits.isEmpty()) {
			return Map.of();
		}
		List<UUID> creditIds = availableCredits.stream().map(ContractCredit::getId).toList();
		Map<UUID, BigDecimal> applied = new HashMap<>();
		for (CreditAppliedTotal total : applications.sumAppliedByCreditIds(creditIds)) {
			applied.put(total.creditId(), total.applied());
		}
		Map<UUID, BigDecimal> balances = new LinkedHashMap<>();
		for (ContractCredit credit : availableCredits) {
			BigDecimal balance = credit.getAmount().subtract(applied.getOrDefault(credit.getId(), ZERO_MONEY));
			balances.put(credit.getId(), balance.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY));
		}
		return balances;
	}

	/** Maps the contract's installments into the engine's input, all from {@code contract}-owned columns. */
	private List<CreditAllocationInput> toAllocationInputs(List<Installment> schedule) {
		Map<UUID, BigDecimal> adjustments = penaltyAdjustmentsByInstallment(schedule);
		List<CreditAllocationInput> inputs = new ArrayList<>(schedule.size());
		for (Installment installment : schedule) {
			BigDecimal resolved = installment.getPaidAmount()
					.add(installment.getSettledAmount())
					.add(installment.getWrittenOffAmount());
			inputs.add(new CreditAllocationInput(installment.getId(), installment.getPeriodNo(),
					installment.getDueDate(), installment.getPrincipalAmount(),
					installment.getRecognizedInterestAmount(), installment.getPenaltyAmount(),
					adjustments.getOrDefault(installment.getId(), ZERO_MONEY), resolved));
		}
		return inputs;
	}

	/**
	 * Consumes AVAILABLE credits oldest-first to fund the engine's {@code appliedTotal}, creating one
	 * {@code contract_credit_application} row per installment a credit funds and flipping a credit to
	 * APPLIED when its running balance reaches 0. One credit may fund many installments and one call may
	 * drain several credits.
	 */
	private List<ContractCreditApplication> consumeCredits(List<ContractCredit> availableCredits,
			Map<UUID, BigDecimal> balanceByCredit, CreditAllocationResult allocation, OffsetDateTime appliedAt) {
		List<ContractCreditApplication> created = new ArrayList<>();
		int creditIndex = 0;
		BigDecimal creditRemaining = availableCredits.isEmpty()
				? ZERO_MONEY
				: balanceByCredit.get(availableCredits.get(0).getId());

		for (CreditAllocationLine line : allocation.lines()) {
			BigDecimal lineRemaining = line.amount();
			while (lineRemaining.signum() > 0) {
				while (creditRemaining.signum() == 0 && creditIndex < availableCredits.size() - 1) {
					creditIndex++;
					creditRemaining = balanceByCredit.get(availableCredits.get(creditIndex).getId());
				}
				ContractCredit credit = availableCredits.get(creditIndex);
				BigDecimal chunk = lineRemaining.min(creditRemaining);
				if (chunk.signum() <= 0) {
					// Defensive: the engine never applies more than the available balance, so this cannot
					// legitimately happen. Fail loudly rather than loop or silently drop the remainder.
					throw new IllegalStateException("credit funding underflow applying to installment "
							+ line.installmentRef());
				}
				ContractCreditApplication application = applications.save(
						new ContractCreditApplication(credit.getId(), line.installmentRef(), chunk, appliedAt));
				created.add(application);

				BigDecimal creditBalanceAfter = balanceByCredit.get(credit.getId()).subtract(chunk)
						.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
				balanceByCredit.put(credit.getId(), creditBalanceAfter);
				credit.recordApplication(chunk, creditBalanceAfter);
				credits.save(credit);

				creditRemaining = creditRemaining.subtract(chunk);
				lineRemaining = lineRemaining.subtract(chunk);
			}
		}
		return created;
	}

	/** Resolved amount per installment (sum across components), for the installment mutator. */
	private static Map<UUID, BigDecimal> resolvedByInstallment(CreditAllocationResult allocation) {
		Map<UUID, BigDecimal> resolved = new LinkedHashMap<>();
		for (CreditAllocationLine line : allocation.lines()) {
			resolved.merge(line.installmentRef(), line.amount(), BigDecimal::add);
		}
		return resolved;
	}

	/**
	 * The credit-application journal (Addendum §2.4): debit {@code TITIPAN_NASABAH} for the total applied,
	 * credit one receivable account per component the application resolved. The debit always equals the
	 * sum of the credits, so the entry balances (invariant 1).
	 */
	private static List<LedgerPostingLine> journalLines(UUID contractId, CreditAllocationResult allocation) {
		List<LedgerPostingLine> lines = new ArrayList<>();
		lines.add(LedgerPostingLine.debit(LedgerAccount.TITIPAN_NASABAH, allocation.appliedTotal(), contractId));
		for (CreditComponent component : List.of(CreditComponent.PENALTY, CreditComponent.INTEREST,
				CreditComponent.PRINCIPAL)) {
			BigDecimal credit = allocation.amountForComponent(component);
			if (credit.signum() > 0) {
				lines.add(LedgerPostingLine.credit(accountOf(component), credit, contractId));
			}
		}
		return lines;
	}

	private static LedgerAccount accountOf(CreditComponent component) {
		return switch (component) {
			case PENALTY -> LedgerAccount.PIUTANG_DENDA;
			case INTEREST -> LedgerAccount.PIUTANG_BUNGA;
			case PRINCIPAL -> LedgerAccount.PIUTANG_POKOK;
		};
	}

	private Map<UUID, BigDecimal> penaltyAdjustmentsByInstallment(List<Installment> schedule) {
		List<UUID> installmentIds = schedule.stream().map(Installment::getId).toList();
		Map<UUID, BigDecimal> totals = new HashMap<>();
		if (installmentIds.isEmpty()) {
			return totals;
		}
		for (Object[] row : installments.sumPenaltyAdjustmentsByInstallmentIds(installmentIds)) {
			totals.put((UUID) row[0], (BigDecimal) row[1]);
		}
		return totals;
	}

	private static List<CreditApplicationHistoryItem> toHistory(List<ContractCreditApplication> created) {
		return created.stream()
				.map(application -> new CreditApplicationHistoryItem(application.getId(), application.getCreditId(),
						application.getInstallmentId(), application.getAmount(), application.getAppliedAt()))
				.toList();
	}

	private static BigDecimal sum(Iterable<BigDecimal> amounts) {
		BigDecimal total = ZERO_MONEY;
		for (BigDecimal amount : amounts) {
			total = total.add(amount);
		}
		return total.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
	}
}
