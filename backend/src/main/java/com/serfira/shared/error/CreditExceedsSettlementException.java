package com.serfira.shared.error;

import org.springframework.http.HttpStatus;

/**
 * 409 — the contract's available customer credit is larger than the settlement's gross amount (E2, task
 * T13, ADR-018 D10). A settlement must consume <b>all</b> available credit, and a cash refund of the
 * surplus is out of the MVP scope, so this case is rejected and <b>writes nothing</b> rather than leaving
 * credit stranded or paying cash out.
 */
public final class CreditExceedsSettlementException extends SerfiraException {

	public CreditExceedsSettlementException(String message) {
		super(ErrorCode.CREDIT_EXCEEDS_SETTLEMENT, HttpStatus.CONFLICT, message);
	}
}
