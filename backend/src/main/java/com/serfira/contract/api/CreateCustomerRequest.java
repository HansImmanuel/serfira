package com.serfira.contract.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Customer payload of a contract creation request (PRD C-1, FE §2.3).
 *
 * <p>Format validation of the identifiers themselves (NIK = exactly 16 digits, phone = 9–15 digits)
 * happens in the application layer through {@code PiiValidator}, because the raw values are also
 * what get normalized into the HMAC lookup columns. An existing customer with the same NIK and phone
 * is reused; the same NIK with a different phone, or a phone owned by another customer, is a 409.
 */
public record CreateCustomerRequest(
		@NotBlank @Size(max = 120) String fullName,
		@NotBlank String nik,
		@NotBlank String phone,
		@Size(max = 500) String address) {
}