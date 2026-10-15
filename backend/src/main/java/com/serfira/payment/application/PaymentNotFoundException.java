package com.serfira.payment.application;

import com.serfira.shared.error.ErrorCode;
import com.serfira.shared.error.SerfiraException;
import org.springframework.http.HttpStatus;

import java.util.UUID;

/**
 * Raised when a payment id does not resolve (E4 void, task T16). Mapped to 404 {@code PAYMENT_NOT_FOUND}.
 *
 * <p>The message carries the identifier only — never customer or financial data.
 */
public class PaymentNotFoundException extends SerfiraException {

	public PaymentNotFoundException(UUID paymentId) {
		super(ErrorCode.PAYMENT_NOT_FOUND, HttpStatus.NOT_FOUND, "payment " + paymentId + " was not found");
	}
}
