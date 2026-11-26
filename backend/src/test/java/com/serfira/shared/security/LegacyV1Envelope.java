package com.serfira.shared.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Seals and opens {@code v1:} envelopes exactly as the pre-T28a write path did ({@code v1:base64(iv ‖ ciphertext|tag)},
 * AES-256-GCM, 12-byte IV, 128-bit tag). Tests use it to stand in for an instance of the previous version: rows it
 * wrote must read under the legacy key, and rows this version writes in v1 mode must open with it (ADR-025 D7).
 */
public final class LegacyV1Envelope {

	private static final SecureRandom RANDOM = new SecureRandom();

	private LegacyV1Envelope() {
	}

	/** Opens a {@code v1:} envelope exactly as the pre-T28a read path did; anything else is refused. */
	public static String open(byte[] aesKey, String envelope) {
		if (!envelope.startsWith("v1:")) {
			throw new IllegalArgumentException("unsupported or malformed PII envelope");
		}
		try {
			byte[] body = Base64.getDecoder().decode(envelope.substring(3));
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new GCMParameterSpec(128, body, 0, 12));
			return new String(cipher.doFinal(body, 12, body.length - 12), StandardCharsets.UTF_8);
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}

	public static String seal(byte[] aesKey, String plaintext) {
		try {
			byte[] iv = new byte[12];
			RANDOM.nextBytes(iv);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new GCMParameterSpec(128, iv));
			byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
			byte[] envelope = new byte[iv.length + sealed.length];
			System.arraycopy(iv, 0, envelope, 0, iv.length);
			System.arraycopy(sealed, 0, envelope, iv.length, sealed.length);
			return "v1:" + Base64.getEncoder().encodeToString(envelope);
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}
}
