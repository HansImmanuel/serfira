package com.serfira.shared.error;

import org.springframework.http.HttpStatus;

/**
 * 409 — a credit-apply request is valid but there is nothing to apply it to: the contract has no
 * available credit, or no due installment carries recognized receivable the credit could reduce
 * (04_GAPS_ADDENDUM.md §2.4, task T14). The request writes nothing.
 */
public final class CreditNotApplicableException extends SerfiraException {

	public CreditNotApplicableException(String message) {
		super(ErrorCode.CREDIT_NOT_APPLICABLE, HttpStatus.CONFLICT, message);
	}
}
