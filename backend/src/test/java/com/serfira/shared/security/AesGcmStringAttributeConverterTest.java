package com.serfira.shared.security;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-004 / AesGcmStringAttributeConverter — at-rest ciphertext behavior: roundtrip, randomized
 * ciphertext per write, versioned envelope, tamper detection, and fast-fail without keys.
 *
 * <p>Keys are bound once and deliberately NOT reset afterwards: the static bridge is JVM-global and
 * shared with the cached @SpringBootTest contexts, so a reset in a unit test could leave a later
 * integration test with no keys.
 */
class AesGcmStringAttributeConverterTest {

	private static final byte[] ENCRYPTION_KEY = "a".repeat(32).getBytes(StandardCharsets.UTF_8);
	private static final byte[] HMAC_KEY = "b".repeat(32).getBytes(StandardCharsets.UTF_8);

	private final AesGcmStringAttributeConverter converter = new AesGcmStringAttributeConverter();

	@BeforeAll
	static void bindKeys() {
		PiiSecuritySupport.configure(ENCRYPTION_KEY, HMAC_KEY);
	}

	@Test
	void roundTripsPlaintext() {
		String sealed = converter.convertToDatabaseColumn("3171012501900001");
		assertThat(converter.convertToEntityAttribute(sealed)).isEqualTo("3171012501900001");
	}

	@Test
	void ciphertextUsesVersionedEnvelopeAndNeverContainsPlaintext() {
		String plaintext = "08123456789";
		String sealed = converter.convertToDatabaseColumn(plaintext);
		assertThat(sealed).startsWith("v1:");
		assertThat(sealed).doesNotContain(plaintext);
	}

	@Test
	void ciphertextIsRandomizedPerWriteWithSamePlaintext() {
		String plaintext = "3171012501900001";
		assertThat(converter.convertToDatabaseColumn(plaintext))
				.isNotEqualTo(converter.convertToDatabaseColumn(plaintext));
	}

	@Test
	void tamperedCiphertextFailsIntegrityCheck() {
		String sealed = converter.convertToDatabaseColumn("3171012501900001");
		byte[] envelope = Base64.getDecoder().decode(sealed.substring("v1:".length()));
		// Flip one byte inside the AEAD payload (after the 12-byte IV) → auth-tag mismatch.
		envelope[envelope.length - 1] ^= 0x01;
		String tampered = "v1:" + Base64.getEncoder().encodeToString(envelope);
		assertThatThrownBy(() -> converter.convertToEntityAttribute(tampered))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("integrity");
	}

	@Test
	void unsupportedEnvelopeIsRejected() {
		assertThatThrownBy(() -> converter.convertToEntityAttribute("3171012501900001"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("malformed");
	}

	@Test
	void nullValuesPassThrough() {
		assertThat(converter.convertToDatabaseColumn(null)).isNull();
		assertThat(converter.convertToEntityAttribute(null)).isNull();
	}

	@Test
	void missingKeysFailFast() {
		PiiSecuritySupport.reset();
		try {
			assertThatIllegalStateException()
					.isThrownBy(() -> converter.convertToDatabaseColumn("3171012501900001"))
					.withMessageContaining("not configured");
		} finally {
			PiiSecuritySupport.configure(ENCRYPTION_KEY, HMAC_KEY);
		}
	}
}