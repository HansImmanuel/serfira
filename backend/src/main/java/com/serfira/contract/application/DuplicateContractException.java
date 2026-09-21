package com.serfira.contract.application;

import com.serfira.shared.error.ErrorCode;
import com.serfira.shared.error.SerfiraException;
import org.springframework.http.HttpStatus;

/**
 * Raised when a customer already has a live (DRAFT or ACTIVE) contract for the same financed asset
 * (invariant 18, 03_DOMAIN_MODEL.md §3). Mapped to 409 {@code DUPLICATE_CONTRACT}.
 *
 * <p>Distinct from {@code CONFLICT} because a client can act on it: the asset is already financed,
 * so the existing contract must be completed/cancelled (or the correct asset selected) instead of
 * retrying.
 */
public class DuplicateContractException extends SerfiraException {

	public DuplicateContractException(String message) {
		super(ErrorCode.DUPLICATE_CONTRACT, HttpStatus.CONFLICT, message);
	}
}