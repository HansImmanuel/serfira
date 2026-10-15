package com.serfira.ledger.application;

import com.serfira.ledger.domain.LedgerAccount;
import com.serfira.ledger.domain.LedgerRefType;
import com.serfira.ledger.infrastructure.JournalLineRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code ledger}-module implementation of {@link ContractLedgerBalancePort} (ADR-001): sums the posted
 * {@code journal_line} rows into the two net balances the consistency check needs
 * ({@link JournalLineRepository#netBalanceByRefType} for {@code KAS} from {@code PAYMENT}, and
 * {@link JournalLineRepository#netBalance} for the three {@code PIUTANG_*} receivable accounts).
 *
 * <p>{@code MANDATORY} propagation, like the ledger's other seams: it joins the consistency step's
 * read-only transaction. Each balance nets its reversals automatically, because a reversal is itself a
 * posted line with debit/credit swapped (ADR-008/ADR-020).
 */
@Service
public class ContractLedgerBalanceService implements ContractLedgerBalancePort {

	private final JournalLineRepository lines;

	public ContractLedgerBalanceService(JournalLineRepository lines) {
		this.lines = lines;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY, readOnly = true)
	public ContractLedgerBalance loadContractBalance(UUID contractId) {
		Objects.requireNonNull(contractId, "contractId");
		BigDecimal paymentCash = lines.netBalanceByRefType(contractId, LedgerAccount.KAS, LedgerRefType.PAYMENT);
		BigDecimal receivable = lines.netBalance(contractId, LedgerAccount.PIUTANG_POKOK)
				.add(lines.netBalance(contractId, LedgerAccount.PIUTANG_BUNGA))
				.add(lines.netBalance(contractId, LedgerAccount.PIUTANG_DENDA));
		return new ContractLedgerBalance(paymentCash, receivable);
	}
}
