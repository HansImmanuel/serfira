package com.serfira.shared.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Base64;

/**
 * Reads the PII keys from configuration/environment and binds them into {@link PiiSecuritySupport} so
 * that converters and hashers (which Hibernate constructs outside Spring) can work.
 *
 * <p>Expected properties (base64): {@code serfira.security.pii.encryption-key} (32 bytes) and
 * {@code serfira.security.pii.hmac-key} (>= 32 bytes). Missing keys fail fast at startup — running with
 * plaintext-fallback or implicit defaults is never allowed (Addendum §9).
 */
@Configuration(proxyBeanMethods = false)
public class PiiSecurityConfiguration {

	private static final String MISSING_KEY_MESSAGE = "'%s' is required (base64-encoded, AES-256 key = 32 bytes)"
			+ " — set it via SERFIRA_PII_ENCRYPTION_KEY / SERFIRA_PII_HMAC_KEY environment variables";

	@Bean
	PiiKeyMaterial piiKeyMaterial(
			@Value("${serfira.security.pii.encryption-key:}") String encryptionKeyBase64,
			@Value("${serfira.security.pii.hmac-key:}") String hmacKeyBase64) {
		if (encryptionKeyBase64 == null || encryptionKeyBase64.isBlank()) {
			throw new IllegalStateException(MISSING_KEY_MESSAGE.formatted("serfira.security.pii.encryption-key"));
		}
		if (hmacKeyBase64 == null || hmacKeyBase64.isBlank()) {
			throw new IllegalStateException(MISSING_KEY_MESSAGE.formatted("serfira.security.pii.hmac-key"));
		}
		PiiSecuritySupport.configure(
				Base64.getDecoder().decode(encryptionKeyBase64),
				Base64.getDecoder().decode(hmacKeyBase64));
		return new PiiKeyMaterial();
	}

	/** Marker bean whose creation triggers the key bootstrap during context startup. */
	public record PiiKeyMaterial() {
	}
}