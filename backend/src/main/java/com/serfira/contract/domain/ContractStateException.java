package com.serfira.contract.domain;

import com.serfira.shared.error.ErrorCode;
import com.serfira.shared.error.SerfiraException;
import org.springframework.http.HttpStatus;

/**
 * Raised when a contract transition is attempted from a status that does not allow it
 * (03_DOMAIN_MODEL.md §1.3: {@code DRAFT → ACTIVE → CLOSED/TERMINATED}; once ACTIVE a contract never
 * returns to DRAFT). Mapped to 409 {@code CONTRACT_STATE_INVALID}.
 */
public class ContractStateException extends SerfiraException {

	public ContractStateException(String message) {
		super(ErrorCode.CONTRACT_STATE_INVALID, HttpStatus.CONFLICT, message);
	}
}