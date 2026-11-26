package com.serfira.contract.application;

import java.util.UUID;

/** A re-keyed customer row did not read back as written. The message names the customer and the check, never a value. */
public class PiiRekeyVerificationException extends IllegalStateException {

	PiiRekeyVerificationException(UUID id, String check) {
		super("re-keyed customer " + id + " failed verification: " + check);
	}
}
