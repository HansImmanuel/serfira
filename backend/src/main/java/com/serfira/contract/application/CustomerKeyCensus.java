package com.serfira.contract.application;

import com.serfira.contract.infrastructure.CustomerPiiRow;
import com.serfira.shared.security.PiiHasher;
import com.serfira.shared.security.PiiSecuritySupport;
import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Read-only tally of the customer PII rows against the active key (T28b): how many are on target, how many still
 * hold each ring key, and which rows block a re-key (unreadable, or one customer stored under two keys). Holds only
 * counts and hashes of rows already seen; it never prints an envelope or a value. Public so a run's outcome can be
 * read outside the package; only the package builds one.
 */
public final class CustomerKeyCensus {

	private final long scannedRows;
	private final SortedMap<String, Long> rowsByKid;
	private final long v1Rows;
	private final long rowsToRekey;
	private final long rowsOffActiveHashes;
	private final long unknownKidRows;
	private final long undecryptableRows;
	private final long duplicateNikRows;
	private final long duplicatePhoneRows;

	private CustomerKeyCensus(Builder b) {
		this.scannedRows = b.scannedRows;
		this.rowsByKid = Collections.unmodifiableSortedMap(new TreeMap<>(b.rowsByKid));
		this.v1Rows = b.v1Rows;
		this.rowsToRekey = b.rowsToRekey;
		this.rowsOffActiveHashes = b.rowsOffActiveHashes;
		this.unknownKidRows = b.unknownKidRows;
		this.undecryptableRows = b.undecryptableRows;
		this.duplicateNikRows = b.duplicateNikRows;
		this.duplicatePhoneRows = b.duplicatePhoneRows;
	}

	static Builder builder() {
		return new Builder();
	}

	/**
	 * Decision 3: on target when both envelopes are {@code v2:<activeKid>:} and both lookup columns equal the
	 * active-key hash of the decrypted {@code nik} and {@code phone}.
	 */
	static boolean onActiveKey(CustomerPiiRow row, String nik, String phone) {
		return PiiSecuritySupport.isActiveV2Envelope(row.nikEnvelope())
				&& PiiSecuritySupport.isActiveV2Envelope(row.phoneEnvelope())
				&& hashesOnActiveKey(row, nik, phone);
	}

	private static boolean hashesOnActiveKey(CustomerPiiRow row, String nik, String phone) {
		return PiiHasher.hashNik(nik).equals(row.nikHash()) && PiiHasher.hashPhone(phone).equals(row.phoneLookup());
	}

	long scannedRows() {
		return scannedRows;
	}

	SortedMap<String, Long> rowsByKid() {
		return rowsByKid;
	}

	public long v1Rows() {
		return v1Rows;
	}

	public long rowsToRekey() {
		return rowsToRekey;
	}

	public long rowsOffActiveHashes() {
		return rowsOffActiveHashes;
	}

	long unknownKidRows() {
		return unknownKidRows;
	}

	long undecryptableRows() {
		return undecryptableRows;
	}

	long duplicateNikRows() {
		return duplicateNikRows;
	}

	long duplicatePhoneRows() {
		return duplicatePhoneRows;
	}

	long unreadableRows() {
		return unknownKidRows + undecryptableRows;
	}

	/** True when a re-key must not run: an unreadable row or a duplicate under the active-key hash. */
	boolean blocksRekey() {
		return unknownKidRows != 0 || undecryptableRows != 0 || duplicateNikRows != 0 || duplicatePhoneRows != 0;
	}

	static final class Builder {

		private final Set<String> nikHashes = new HashSet<>();
		private final Set<String> phoneHashes = new HashSet<>();
		private final TreeMap<String, Long> rowsByKid = new TreeMap<>();
		private long scannedRows;
		private long v1Rows;
		private long rowsToRekey;
		private long rowsOffActiveHashes;
		private long unknownKidRows;
		private long undecryptableRows;
		private long duplicateNikRows;
		private long duplicatePhoneRows;

		private Builder() {
		}

		void accept(CustomerPiiRow row) {
			scannedRows++;
			Optional<String> nikKid = PiiSecuritySupport.sealingKid(row.nikEnvelope());
			Optional<String> phoneKid = PiiSecuritySupport.sealingKid(row.phoneEnvelope());
			if (row.nikEnvelope().startsWith("v1:") || row.phoneEnvelope().startsWith("v1:")) {
				v1Rows++;
			}
			nikKid.ifPresent(kid -> rowsByKid.merge(kid, 1L, Long::sum));
			// A row sealed under two different keys is held by both.
			phoneKid.filter(kid -> !nikKid.map(kid::equals).orElse(false))
					.ifPresent(kid -> rowsByKid.merge(kid, 1L, Long::sum));
			if (nikKid.isEmpty() || phoneKid.isEmpty()) {
				unknownKidRows++;
				return;
			}
			String nik;
			String phone;
			try {
				nik = PiiSecuritySupport.decrypt(row.nikEnvelope());
				phone = PiiSecuritySupport.decrypt(row.phoneEnvelope());
			} catch (IllegalArgumentException e) {
				undecryptableRows++;
				return;
			}
			if (!hashesOnActiveKey(row, nik, phone)) {
				rowsOffActiveHashes++;
			}
			if (!onActiveKey(row, nik, phone)) {
				rowsToRekey++;
			}
			if (!nikHashes.add(PiiHasher.hashNik(nik))) {
				duplicateNikRows++;
			}
			if (!phoneHashes.add(PiiHasher.hashPhone(phone))) {
				duplicatePhoneRows++;
			}
		}

		CustomerKeyCensus build() {
			return new CustomerKeyCensus(this);
		}
	}
}
