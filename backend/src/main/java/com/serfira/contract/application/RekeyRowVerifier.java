package com.serfira.contract.application;

import com.serfira.contract.infrastructure.CustomerPiiRow;
import com.serfira.shared.security.PiiSecuritySupport;
import java.util.UUID;

/** Post-write check of one re-keyed customer row: envelopes under the active key, round-tripping, active-key hashes. */
final class RekeyRowVerifier {

	private RekeyRowVerifier() {
	}

	/** The plaintext and active-key hashes a row must hold after re-keying. {@code toString()} is the id only. */
	record RekeyedValues(UUID id, String nik, String phone, String nikHash, String phoneLookup) {

		@Override
		public String toString() {
			return id.toString();
		}
	}

	/** @throws PiiRekeyVerificationException naming the first failed check; the message carries no value */
	static void verify(RekeyedValues expected, CustomerPiiRow reread) {
		check(expected.id(), "nik", expected.nik(), reread.nikEnvelope(), expected.nikHash(), reread.nikHash(),
				"nik_hash");
		check(expected.id(), "phone", expected.phone(), reread.phoneEnvelope(), expected.phoneLookup(),
				reread.phoneLookup(), "phone_lookup");
	}

	private static void check(UUID id, String field, String plaintext, String envelope, String expectedHash,
			String actualHash, String hashColumn) {
		if (!PiiSecuritySupport.isActiveV2Envelope(envelope)) {
			throw new PiiRekeyVerificationException(id, field + " envelope is not under the active key");
		}
		String decrypted;
		try {
			decrypted = PiiSecuritySupport.decrypt(envelope);
		} catch (IllegalArgumentException e) {
			decrypted = null;
		}
		if (!plaintext.equals(decrypted)) {
			throw new PiiRekeyVerificationException(id, field + " does not decrypt to its original value");
		}
		if (!expectedHash.equals(actualHash)) {
			throw new PiiRekeyVerificationException(id, hashColumn + " is not the active-key hash");
		}
	}
}
