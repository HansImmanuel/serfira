package com.serfira.contract.application;

import com.serfira.shared.idempotency.IdempotencyKeyUsage.FingerprintKeyUsage;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Which ring keys can be retired (T28b, Decision 7). The active key is {@code ACTIVE}. Any other ring key is
 * {@code RETIRABLE} only when no customer envelope, no off-active hash, no unexpired fingerprint and no unreadable
 * row remains; a fingerprint under a key that is not in the ring is {@code MISSING_FROM_RING}. Holds kids and
 * counts only.
 */
public record PiiRetirementReport(String activeKid, long rowsOffActiveHashes, long unreadableRows,
		List<KeyVerdict> keys) {

	public enum Verdict {
		ACTIVE, RETIRABLE, NOT_RETIRABLE, MISSING_FROM_RING
	}

	public record KeyVerdict(String kid, Verdict verdict, long envelopeRows, long unexpiredFingerprints) {
	}

	static PiiRetirementReport of(CustomerKeyCensus census, FingerprintKeyUsage fingerprints, List<String> ringKids,
			String activeKid, Optional<String> legacyKid) {
		List<KeyVerdict> keys = new ArrayList<>();
		for (String kid : ringKids) {
			long envelopeRows = census.rowsByKid().getOrDefault(kid, 0L);
			long unexpired = fingerprints.byKid().getOrDefault(kid, 0L)
					+ (legacyKid.filter(kid::equals).isPresent() ? fingerprints.unlabelled() : 0L);
			Verdict verdict;
			if (kid.equals(activeKid)) {
				verdict = Verdict.ACTIVE;
			} else if (envelopeRows == 0 && census.rowsOffActiveHashes() == 0 && unexpired == 0
					&& census.unreadableRows() == 0) {
				verdict = Verdict.RETIRABLE;
			} else {
				verdict = Verdict.NOT_RETIRABLE;
			}
			keys.add(new KeyVerdict(kid, verdict, envelopeRows, unexpired));
		}
		for (String kid : new TreeSet<>(fingerprints.byKid().keySet())) {
			long unexpired = fingerprints.byKid().get(kid);
			if (!ringKids.contains(kid) && unexpired > 0) {
				keys.add(new KeyVerdict(kid, Verdict.MISSING_FROM_RING, 0L, unexpired));
			}
		}
		return new PiiRetirementReport(activeKid, census.rowsOffActiveHashes(), census.unreadableRows(),
				List.copyOf(keys));
	}

	public Optional<KeyVerdict> verdictFor(String kid) {
		return keys.stream().filter(key -> key.kid().equals(kid)).findFirst();
	}

	/** One line per key plus a summary; kids, counts and verdicts only. */
	public List<String> logLines() {
		List<String> lines = new ArrayList<>();
		for (KeyVerdict key : keys) {
			lines.add("pii-rekey key kid=" + key.kid() + " verdict=" + key.verdict() + " envelopeRows="
					+ key.envelopeRows() + " unexpiredFingerprints=" + key.unexpiredFingerprints());
		}
		lines.add("pii-rekey summary activeKid=" + activeKid + " rowsOffActiveHashes=" + rowsOffActiveHashes
				+ " unreadableRows=" + unreadableRows);
		return lines;
	}
}
