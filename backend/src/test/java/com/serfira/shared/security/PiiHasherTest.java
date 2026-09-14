package com.serfira.shared.security;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-004 / PiiHasher — deterministic keyed lookup hashes with normalization.
 *
 * <p>Keys are bound once and deliberately NOT reset afterwards: the static bridge is JVM-global and
 * shared with the cached @SpringBootTest contexts (whose own keys come from the test profile), so a
 * reset in a unit test could leave a later integration test with no keys.
 */
class PiiHasherTest {

	private static final byte[] ENCRYPTION_KEY = "a".repeat(32).getBytes(StandardCharsets.UTF_8);
	private static final byte[] HMAC_KEY = "b".repeat(32).getBytes(StandardCharsets.UTF_8);

	@BeforeAll
	static void bindKeys() {
		PiiSecuritySupport.configure(ENCRYPTION_KEY, HMAC_KEY);
	}

	@Test
	void hashIsDeterministic() {
		assertThat(PiiHasher.hashNik("3171012501900001")).isEqualTo(PiiHasher.hashNik("3171012501900001"));
		assertThat(PiiHasher.hashPhone("08123456789")).isEqualTo(PiiHasher.hashPhone("08123456789"));
	}

	@Test
	void nikNormalizationMakesSeparatorsEquivalent() {
		assertThat(PiiHasher.hashNik("3171 0125 0190 0001"))
				.isEqualTo(PiiHasher.hashNik("3171-0125-0190-0001"))
				.isEqualTo(PiiHasher.hashNik("3171012501900001"));
	}

	@Test
	void phoneNormalizationMapsLocalInternationalAndPunctuationToSameValue() {
		assertThat(PiiHasher.hashPhone("0812-3456-7890"))
				.isEqualTo(PiiHasher.hashPhone("+6281234567890"))
				.isEqualTo(PiiHasher.hashPhone("6281234567890"))
				.isEqualTo(PiiHasher.hashPhone("0812 3456 7890"));
	}

	@Test
	void hashDependsOnTheKey() {
		String withOriginalKey = PiiHasher.hashNik("3171012501900001");
		PiiSecuritySupport.configure(ENCRYPTION_KEY, "c".repeat(32).getBytes(StandardCharsets.UTF_8));
		try {
			assertThat(PiiHasher.hashNik("3171012501900001")).isNotEqualTo(withOriginalKey);
		} finally {
			PiiSecuritySupport.configure(ENCRYPTION_KEY, HMAC_KEY);
		}
	}

	@Test
	void differentValuesProduceDifferentHashes() {
		assertThat(PiiHasher.hashNik("3171012501900001")).isNotEqualTo(PiiHasher.hashNik("3171012501900002"));
		assertThat(PiiHasher.hashPhone("08123456788")).isNotEqualTo(PiiHasher.hashPhone("08123456789"));
	}
}