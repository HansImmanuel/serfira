package com.serfira.shared.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Static bridge between JPA {@link jakarta.persistence.AttributeConverter}s / hashers and the Spring
 * beans that own the PII keys — the same pattern as {@code AuditSupport}: Hibernate instantiates
 * converters itself, so Spring beans cannot be injected into them directly.
 *
 * <p>Implements ADR-001/ADR-004 at-rest protection over the key ring of ADR-025: values are sealed with
 * AES-256-GCM (random 12-byte IV + 128-bit auth tag) under the <b>active</b> key and returned as the envelope
 * {@code v2:<kid>:<base64(iv ‖ ciphertext|tag)>}, or as {@code v1:<base64(...)>} while the ring still writes
 * {@code v1} for compatibility with earlier versions (D7). Reads accept {@code v2} under any ring key and
 * {@code v1} under the <b>legacy</b> key. Lookup/uniqueness values are HMAC-SHA-256 digests of the
 * *normalized* input ({@link PiiHasher}): {@link #hmacHex} under the active key for writers, and
 * {@link #hmacCandidates} under every ring key for matching — ciphertext is randomized per write and never used
 * for equality or indexing.
 *
 * <p>Every envelope failure is an {@link IllegalArgumentException} whose message never repeats any part of the
 * envelope — not even its {@code kid}, which in a corrupted value could be a NIK — nor the plaintext.
 */
public final class PiiSecuritySupport {

	private static final String CIPHER_ALGORITHM = "AES/GCM/NoPadding";
	private static final String V1_PREFIX = "v1:";
	private static final String V2_PREFIX = "v2:";
	private static final int IV_BYTES = 12;
	private static final int TAG_BITS = 128;
	private static final SecureRandom RANDOM = new SecureRandom();

	private static volatile PiiKeyRing ring;

	private PiiSecuritySupport() {
	}

	/** Binds the PII key ring, called by {@link PiiSecurityConfiguration} at context startup. */
	public static synchronized void configure(PiiKeyRing keyRing) {
		if (keyRing == null) {
			throw new IllegalArgumentException("PII key ring is required");
		}
		ring = keyRing;
	}

	/**
	 * Binds a ring of one key ({@link PiiKeyRing#single}).
	 *
	 * @param encryptionKeyBytes 32-byte AES-256 key
	 * @param hmacKeyBytes       at least 32-byte HMAC-SHA-256 key
	 */
	public static synchronized void configure(byte[] encryptionKeyBytes, byte[] hmacKeyBytes) {
		try {
			configure(PiiKeyRing.single(encryptionKeyBytes, hmacKeyBytes));
		} catch (IllegalStateException e) {
			throw new IllegalArgumentException(e.getMessage(), e);
		}
	}

	/** Unbinds the keys (used between tests only — production keys must NEVER be reset). */
	public static synchronized void reset() {
		ring = null;
	}

	/**
	 * Seals {@code plaintext} under the active key, as {@code v2:<activeKid>:base64(...)} or — while the ring still
	 * writes {@code v1} (ADR-025 D7) — as {@code v1:base64(...)}, which every earlier version reads.
	 */
	public static String encrypt(String plaintext) {
		PiiKeyRing keys = requireKeys();
		if (plaintext == null) {
			return null;
		}
		PiiKey active = keys.active();
		try {
			Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
			byte[] iv = new byte[IV_BYTES];
			RANDOM.nextBytes(iv);
			cipher.init(Cipher.ENCRYPT_MODE, active.encryptionKey(), new GCMParameterSpec(TAG_BITS, iv));
			byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
			byte[] envelope = new byte[iv.length + sealed.length];
			System.arraycopy(iv, 0, envelope, 0, iv.length);
			System.arraycopy(sealed, 0, envelope, iv.length, sealed.length);
			String body = Base64.getEncoder().encodeToString(envelope);
			return keys.writeEnvelope() == PiiEnvelopeVersion.V1 ? V1_PREFIX + body : V2_PREFIX + active.kid() + ":" + body;
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("failed to encrypt PII value", e);
		}
	}

	/** Unseals a {@code v2:<kid>:} envelope under that ring key, or a {@code v1:} envelope under the legacy key. */
	public static String decrypt(String ciphertext) {
		PiiKeyRing keys = requireKeys();
		if (ciphertext == null) {
			return null;
		}
		if (ciphertext.startsWith(V1_PREFIX)) {
			PiiKey legacy = keys.legacy().orElseThrow(() -> new IllegalArgumentException(
					"v1 PII envelope but no legacy PII key is configured"));
			return open(legacy.encryptionKey(), ciphertext.substring(V1_PREFIX.length()));
		}
		if (ciphertext.startsWith(V2_PREFIX)) {
			String rest = ciphertext.substring(V2_PREFIX.length());
			int separator = rest.indexOf(':');
			if (separator < 0) {
				throw malformed();
			}
			String kid = rest.substring(0, separator);
			if (!PiiKey.isValidKid(kid)) {
				throw malformed();
			}
			// The kid is not echoed: a 16-digit NIK matches the kid syntax, so a corrupted envelope could carry one.
			PiiKey key = keys.find(kid).orElseThrow(() -> new IllegalArgumentException(
					"PII envelope names a key id that is not in the key ring"));
			return open(key.encryptionKey(), rest.substring(separator + 1));
		}
		throw malformed();
	}

	/** Deterministic keyed digest of a {@code normalizedValue} under the active key — the value writers store. */
	public static String hmacHex(String normalizedValue) {
		return hmac(requireKeys().active(), normalizedValue);
	}

	/**
	 * The digest of {@code normalizedValue} under every ring key, the active key first and the rest by ascending
	 * {@code kid} — the values a lookup matches, so a row hashed under an older key is still found.
	 */
	public static List<String> hmacCandidates(String normalizedValue) {
		return requireKeys().candidateOrder().stream().map(key -> hmac(key, normalizedValue)).toList();
	}

	private static String open(SecretKeySpec key, String encodedBody) {
		byte[] envelope;
		try {
			envelope = Base64.getDecoder().decode(encodedBody);
		} catch (IllegalArgumentException e) {
			throw malformed();
		}
		if (envelope.length <= IV_BYTES) {
			throw new IllegalArgumentException("PII envelope is too short");
		}
		try {
			Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
			cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, envelope, 0, IV_BYTES));
			return new String(cipher.doFinal(envelope, IV_BYTES, envelope.length - IV_BYTES), StandardCharsets.UTF_8);
		} catch (GeneralSecurityException e) {
			// AEADBadTagException — tampered, relabelled or foreign-key ciphertext; never silently accept corruption.
			throw new IllegalArgumentException("PII ciphertext integrity check failed", e);
		}
	}

	private static String hmac(PiiKey key, String normalizedValue) {
		try {
			Mac mac = Mac.getInstance(PiiKey.HMAC_ALGORITHM);
			mac.init(key.hmacKey());
			return HexFormat.of().formatHex(mac.doFinal(normalizedValue.getBytes(StandardCharsets.UTF_8)));
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("failed to compute PII lookup hash", e);
		}
	}

	private static IllegalArgumentException malformed() {
		return new IllegalArgumentException("unsupported or malformed PII envelope");
	}

	private static PiiKeyRing requireKeys() {
		PiiKeyRing keys = ring;
		if (keys == null) {
			throw new IllegalStateException(
					"PII keys not configured; set serfira.security.pii.encryption-key and serfira.security.pii.hmac-key");
		}
		return keys;
	}
}
