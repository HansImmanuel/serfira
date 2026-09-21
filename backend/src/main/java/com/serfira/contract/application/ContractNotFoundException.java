package com.serfira.contract.application;

import com.serfira.shared.error.ErrorCode;
import com.serfira.shared.error.SerfiraException;
import org.springframework.http.HttpStatus;

import java.util.UUID;

/**
 * Raised when a contract id does not resolve. Mapped to 404 {@code CONTRACT_NOT_FOUND}.
 *
 * <p>The message carries the identifier only — never customer data.
 */
public class ContractNotFoundException extends SerfiraException {

	public ContractNotFoundException(UUID contractId) {
		super(ErrorCode.CONTRACT_NOT_FOUND, HttpStatus.NOT_FOUND, "contract " + contractId + " was not found");
	}
}