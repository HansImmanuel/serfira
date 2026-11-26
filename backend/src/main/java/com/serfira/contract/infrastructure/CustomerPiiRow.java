package com.serfira.contract.infrastructure;

import java.util.UUID;

/**
 * One customer's stored PII columns exactly as persisted: the two envelopes and the two lookup hashes (T28b).
 * {@link #toString()} prints the id only, so a row can never put an envelope or a hash in a log line or an exception.
 */
public record CustomerPiiRow(UUID id, String nikEnvelope, String nikHash, String phoneEnvelope, String phoneLookup) {

	@Override
	public String toString() {
		return "CustomerPiiRow[id=" + id + "]";
	}
}
