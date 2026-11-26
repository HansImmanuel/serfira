package com.serfira.shared.security;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The immutable PII key ring (ADR-025): every key that may still have sealed a value or signed a lookup hash, one
 * of them <b>active</b> (all new writes and hashes) and optionally one <b>legacy</b> key that reads the pre-ring
 * {@code v1:} envelopes.
 *
 * <p>Construction is the startup validation: the active and legacy ids exist, no id appears twice, and no two ids
 * share an encryption key or an HMAC key. The last rule is what makes a body relabelled with another ring id
 * fail its GCM tag instead of decrypting.
 *
 * <p>The ring also fixes the envelope new values are written in ({@link PiiEnvelopeVersion}, ADR-025 D7). A
 * {@code v1} envelope carries no key id, and every {@code v1:} row on disk was sealed under the pre-ring key
 * {@code k1}, the only key a pre-T28a version knows. So {@code v1} writes require the active key and the legacy
 * key to both be {@code k1}: a {@code v1} row under any other key would be unreadable by that version, and
 * indistinguishable from the {@code k1} rows to this one.
 */
public final class PiiKeyRing {

	static final String SINGLE_KID = "k1";

	private final Map<String, PiiKey> keys;
	private final PiiKey active;
	private final PiiKey legacy;
	private final List<PiiKey> candidateOrder;
	private final PiiEnvelopeVersion writeEnvelope;

	/**
	 * There is deliberately no constructor that defaults the envelope: {@code v1} is what D7's compatibility needs
	 * and {@code v2} is what rotation needs, so every caller states which one it writes.
	 *
	 * @param keys          the ring entries
	 * @param activeKid     the key for new writes and hashes; must be in {@code keys}
	 * @param legacyKid     the key that reads {@code v1:} envelopes, or {@code null} for none; must be in
	 *                      {@code keys}
	 * @param writeEnvelope the envelope new values are written in; {@code V1} requires {@code activeKid} and
	 *                      {@code legacyKid} to both be {@code k1}
	 */
	public PiiKeyRing(List<PiiKey> keys, String activeKid, String legacyKid, PiiEnvelopeVersion writeEnvelope) {
		if (keys == null || keys.isEmpty()) {
			throw new IllegalStateException("the PII key ring is empty");
		}
		Map<String, PiiKey> byKid = new LinkedHashMap<>();
		for (PiiKey key : keys) {
			if (byKid.putIfAbsent(key.kid(), key) != null) {
				throw new IllegalStateException("PII key id '" + key.kid() + "' is configured twice");
			}
		}
		List<PiiKey> entries = new ArrayList<>(byKid.values());
		for (int i = 0; i < entries.size(); i++) {
			for (int j = i + 1; j < entries.size(); j++) {
				PiiKey a = entries.get(i);
				PiiKey b = entries.get(j);
				if (MessageDigest.isEqual(a.encryptionKey().getEncoded(), b.encryptionKey().getEncoded())
						|| MessageDigest.isEqual(a.hmacKey().getEncoded(), b.hmacKey().getEncoded())) {
					throw new IllegalStateException(
							"PII keys '" + a.kid() + "' and '" + b.kid() + "' share key material");
				}
			}
		}
		this.keys = Map.copyOf(byKid);
		this.active = requireMember(activeKid, "active");
		this.legacy = legacyKid == null ? null : requireMember(legacyKid, "legacy");
		List<PiiKey> order = new ArrayList<>();
		order.add(active);
		byKid.values().stream()
				.filter(key -> !key.kid().equals(active.kid()))
				.sorted(Comparator.comparing(PiiKey::kid))
				.forEach(order::add);
		this.candidateOrder = List.copyOf(order);
		if (writeEnvelope == null) {
			throw new IllegalStateException("the PII write envelope is required");
		}
		if (writeEnvelope == PiiEnvelopeVersion.V1) {
			requireV1Writable();
		}
		this.writeEnvelope = writeEnvelope;
	}

	/**
	 * A ring of one key, {@code k1}, that is both active and legacy and writes {@code v1}: exactly the pre-ring
	 * behaviour, on disk included.
	 */
	public static PiiKeyRing single(byte[] encryptionKey, byte[] hmacKey) {
		return new PiiKeyRing(List.of(PiiKey.of(SINGLE_KID, encryptionKey, hmacKey)), SINGLE_KID, SINGLE_KID,
				PiiEnvelopeVersion.V1);
	}

	/** The envelope new values are written in. */
	public PiiEnvelopeVersion writeEnvelope() {
		return writeEnvelope;
	}

	public PiiKey active() {
		return active;
	}

	public Optional<PiiKey> find(String kid) {
		return Optional.ofNullable(keys.get(kid));
	}

	public Optional<PiiKey> legacy() {
		return Optional.ofNullable(legacy);
	}

	/** Every ring key, the active one first and the rest by ascending {@code kid}. */
	public List<PiiKey> candidateOrder() {
		return candidateOrder;
	}

	/**
	 * {@code v1} writes are sealed under the active key but read back under the legacy key, and every existing
	 * {@code v1:} row is under {@code k1}. Both must therefore be {@code k1}; moving the legacy key to a new active
	 * key would strand the existing rows and write new {@code v1:} rows no pre-T28a version can read.
	 */
	private void requireV1Writable() {
		if (legacy == null) {
			throw new IllegalStateException("no legacy PII key is configured, so v1 PII envelopes can be neither read "
					+ "nor written; set 'serfira.security.pii.legacy-kid' (SERFIRA_SECURITY_PII_LEGACYKID) to "
					+ SINGLE_KID + ", the key that sealed the existing v1: rows (ADR-025 D7)");
		}
		if (!SINGLE_KID.equals(active.kid()) || !SINGLE_KID.equals(legacy.kid())) {
			throw new IllegalStateException("v1 PII envelopes carry no key id and every v1: row is sealed under "
					+ SINGLE_KID + ", so v1 writes need both 'serfira.security.pii.active-kid' and "
					+ "'serfira.security.pii.legacy-kid' to be " + SINGLE_KID + "; to write under another key, set "
					+ "'serfira.security.pii.write-envelope' to v2 (SERFIRA_SECURITY_PII_WRITEENVELOPE) once every "
					+ "running version reads v2, and keep legacy-kid at " + SINGLE_KID + " (ADR-025 D7)");
		}
	}

	private PiiKey requireMember(String kid, String role) {
		if (!PiiKey.isValidKid(kid)) {
			// Never echo a value that is not a key id: it may be a key pasted into the wrong variable.
			throw new IllegalStateException("the " + role
					+ " PII key id is not a valid key id (1-16 lowercase letters or digits)");
		}
		PiiKey key = keys.get(kid);
		if (key == null) {
			throw new IllegalStateException("the " + role + " PII key id '" + kid + "' is not in the key ring");
		}
		return key;
	}
}
