package com.serfira.ledger.application;

import java.math.BigDecimal;

/**
 * The {@code ledger}-owned balances the daily consistency check (F1, task T17, Addendum §7.1) reconciles
 * for one contract, each a net {@code Σ(debit − credit)} that already nets any reversal to zero.
 *
 * <ul>
 *   <li>{@code paymentCashReceived} — net {@code KAS} movement across {@code PAYMENT} entries; the cash
 *       recorded in the ledger for the contract's still-POSTED payments (check A, {@code PAYMENT_CASH}).</li>
 *   <li>{@code receivableBalance} — net {@code PIUTANG_POKOK + PIUTANG_BUNGA + PIUTANG_DENDA}; the recognized
 *       receivable the ledger still carries for the contract (check C, {@code RECEIVABLE_INSTALLMENT}).</li>
 * </ul>
 *
 * @param paymentCashReceived net {@code KAS} from {@code PAYMENT} entries, scale-2 (sign preserved)
 * @param receivableBalance   net {@code PIUTANG_*} balance, scale-2 (sign preserved)
 */
public record ContractLedgerBalance(BigDecimal paymentCashReceived, BigDecimal receivableBalance) {

	public ContractLedgerBalance {
		if (paymentCashReceived == null || receivableBalance == null) {
			throw new IllegalArgumentException("ledger balances must not be null");
		}
	}
}
