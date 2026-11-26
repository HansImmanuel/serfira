package com.serfira.shared.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Seals a {@code v1:} envelope exactly as the pre-T28a write path did ({@code v1:base64(iv ‖ ciphertext|tag)},
 * AES-256-GCM, 12-byte IV, 128-bit tag). Production code no longer writes {@code v1}; tests need it to prove that
 * rows written before the key ring still read under the legacy key (ADR-025).
 */
public final class LegacyV1Envelope {

	private static final SecureRandom RANDOM = new SecureRandom();

	private LegacyV1Envelope() {
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
