package com.serfira.shared.security;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Static bridge between JPA {@link jakarta.persistence.AttributeConverter}s / hashers and the Spring
 * beans that own the PII keys — the same pattern as {@code AuditSupport}: Hibernate instantiates
 * converters itself, so Spring beans cannot be injected into them directly.
 *
 * <p>Implements ADR-001/ADR-004 at-rest protection: values are sealed with AES-256-GCM
 * (random 12-byte IV + 128-bit auth tag) and returned as a versioned envelope
 * {@code v1:<base64(iv ‖ ciphertext|tag)>}. Lookup/uniqueness values are HMAC-SHA-256 digests of the
 * *normalized* input ({@link PiiHasher}) — ciphertext is randomized per write and never used for
 * equality or indexing.
 */
public final class PiiSecuritySupport {

	private static final String CIPHER_ALGORITHM = "AES/GCM/NoPadding";
	private static final String HMAC_ALGORITHM = "HmacSHA256";
	private static final String VERSION_PREFIX = "v1:";
	private static final int IV_BYTES = 12;
	private static final int TAG_BITS = 128;
	private static final int AES_KEY_BYTES = 32;
	private static final SecureRandom RANDOM = new SecureRandom();

	private static volatile SecretKeySpec encryptionKey;
	private static volatile SecretKeySpec hmacKey;

	private PiiSecuritySupport() {
	}

	/**
	 * Binds the PII keys, called by {@link PiiSecurityConfiguration} at context startup.
	 *
	 * @param encryptionKeyBytes 32-byte AES-256 key
	 * @param hmacKeyBytes       at least 32-byte HMAC-SHA-256 key
	 */
	public static synchronized void configure(byte[] encryptionKeyBytes, byte[] hmacKeyBytes) {
		if (encryptionKeyBytes == null || encryptionKeyBytes.length != AES_KEY_BYTES) {
			throw new IllegalArgumentException("AES-256-GCM key must be exactly " + AES_KEY_BYTES + " bytes");
		}
		if (hmacKeyBytes == null || hmacKeyBytes.length < AES_KEY_BYTES) {
			throw new IllegalArgumentException("HMAC-SHA-256 key must be at least " + AES_KEY_BYTES + " bytes");
		}
		encryptionKey = new SecretKeySpec(encryptionKeyBytes, "AES");
		hmacKey = new SecretKeySpec(hmacKeyBytes, HMAC_ALGORITHM);
	}

	/** Unbinds the keys (used between tests only — production keys must NEVER be reset). */
	public static synchronized void reset() {
		encryptionKey = null;
		hmacKey = null;
	}

	/** Seals {@code plaintext} into a {@code v1:base64(...)} ciphertext envelope. */
	public static String encrypt(String plaintext) {
		requireKeys();
		if (plaintext == null) {
			return null;
		}
		try {
			Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
			byte[] iv = new byte[IV_BYTES];
			RANDOM.nextBytes(iv);
			cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_BITS, iv));
			byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
			byte[] envelope = new byte[iv.length + sealed.length];
			System.arraycopy(iv, 0, envelope, 0, iv.length);
			System.arraycopy(sealed, 0, envelope, iv.length, sealed.length);
			return VERSION_PREFIX + Base64.getEncoder().encodeToString(envelope);
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("failed to encrypt PII value", e);
		}
	}

	/** Unseals a {@code v1:} envelope back to the plaintext. */
	public static String decrypt(String ciphertext) {
		requireKeys();
		if (ciphertext == null) {
			return null;
		}
		if (!ciphertext.startsWith(VERSION_PREFIX)) {
			throw new IllegalArgumentException("unsupported or malformed PII envelope");
		}
		try {
			byte[] envelope = Base64.getDecoder().decode(ciphertext.substring(VERSION_PREFIX.length()));
			if (envelope.length <= IV_BYTES) {
				throw new IllegalArgumentException("PII envelope is too short");
			}
			Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
			cipher.init(Cipher.DECRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_BITS, envelope, 0, IV_BYTES));
			return new String(cipher.doFinal(envelope, IV_BYTES, envelope.length - IV_BYTES), StandardCharsets.UTF_8);
		} catch (GeneralSecurityException e) {
			// AEADBadTagException — tampered or foreign-key ciphertext; never silently accept corruption.
			throw new IllegalArgumentException("PII ciphertext integrity check failed", e);
		}
	}

	/** Determinisistic keyed digest of a {@code normalizedValue} — the search/uniqueness key. */
	public static String hmacHex(String normalizedValue) {
		requireKeys();
		try {
			Mac mac = Mac.getInstance(HMAC_ALGORITHM);
			mac.init(hmacKey);
			return HexFormat.of().formatHex(mac.doFinal(normalizedValue.getBytes(StandardCharsets.UTF_8)));
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("failed to compute PII lookup hash", e);
		}
	}

	private static void requireKeys() {
		if (encryptionKey == null || hmacKey == null) {
			throw new IllegalStateException(
					"PII keys not configured; set serfira.security.pii.encryption-key and serfira.security.pii.hmac-key");
		}
	}
}