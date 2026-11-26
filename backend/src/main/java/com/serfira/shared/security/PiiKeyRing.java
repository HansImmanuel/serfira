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
 */
public final class PiiKeyRing {

	static final String SINGLE_KID = "k1";

	private final Map<String, PiiKey> keys;
	private final PiiKey active;
	private final PiiKey legacy;
	private final List<PiiKey> candidateOrder;

	/**
	 * @param keys      the ring entries
	 * @param activeKid the key for new writes and hashes; must be in {@code keys}
	 * @param legacyKid the key that reads {@code v1:} envelopes, or {@code null} for none; must be in {@code keys}
	 */
	public PiiKeyRing(List<PiiKey> keys, String activeKid, String legacyKid) {
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
	}

	/** A ring of one key, {@code k1}, that is both active and legacy: exactly the pre-ring behaviour. */
	public static PiiKeyRing single(byte[] encryptionKey, byte[] hmacKey) {
		return new PiiKeyRing(List.of(PiiKey.of(SINGLE_KID, encryptionKey, hmacKey)), SINGLE_KID, SINGLE_KID);
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

	private PiiKey requireMember(String kid, String role) {
		PiiKey key = kid == null ? null : keys.get(kid);
		if (key == null) {
			throw new IllegalStateException("the " + role + " PII key id '" + kid + "' is not in the key ring");
		}
		return key;
	}
}
