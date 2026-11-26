package com.serfira.shared.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Reads the PII key ring from configuration/environment and binds it into {@link PiiSecuritySupport} so that
 * converters and hashers (which Hibernate constructs outside Spring) can work.
 *
 * <p>The existing properties (base64) {@code serfira.security.pii.encryption-key} (32 bytes) and
 * {@code serfira.security.pii.hmac-key} (>= 32 bytes) still configure a ring of one key, {@code k1}; ring entries,
 * the active and the legacy key id are additive (ADR-025, {@link PiiKeyRingProperties}). Missing or invalid keys
 * fail fast at startup — running with plaintext-fallback or implicit defaults is never allowed (Addendum §9).
 */
@Configuration(proxyBeanMethods = false)
public class PiiSecurityConfiguration {

	@Bean
	PiiKeyMaterial piiKeyMaterial(Environment environment) {
		PiiSecuritySupport.configure(PiiKeyRingProperties.load(environment));
		return new PiiKeyMaterial();
	}

	/** Marker bean whose creation triggers the key bootstrap during context startup. */
	public record PiiKeyMaterial() {
	}
}
