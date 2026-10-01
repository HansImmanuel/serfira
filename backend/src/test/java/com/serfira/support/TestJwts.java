package com.serfira.support;

import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;

/**
 * Test-only HS256 token minting for the resource-server chain (T7). One place for every token shape the
 * security ITs need, instead of a copied {@code jwtFor} per suite.
 *
 * <p>The signing key mirrors {@code serfira.security.jwt.secret-base64} in
 * {@code src/test/resources/application.properties}. It is a test-only key and must never be used elsewhere.
 */
public final class TestJwts {

	/** Mirrors serfira.security.jwt.secret-base64 in src/test/resources/application.properties. */
	public static final byte[] TEST_SECRET = Base64.getDecoder()
			.decode("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");

	/** A different, equally long key: a token signed with it must be rejected. */
	public static final byte[] WRONG_SECRET = Base64.getDecoder()
			.decode("ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA=");

	private static final long VALIDITY_SECONDS = 600;

	private TestJwts() {
	}

	/** A valid token for {@code subject} with the given {@code roles} array. */
	public static String forRoles(UUID subject, String... roles) {
		return sign(validClaims(subject.toString()).claim("roles", List.of(roles)).build(), TEST_SECRET);
	}

	/** A valid token with no {@code roles} claim at all. */
	public static String withoutRolesClaim(UUID subject) {
		return sign(validClaims(subject.toString()).build(), TEST_SECRET);
	}

	/** A valid token whose {@code roles} claim is the given raw value (for example a plain string). */
	public static String withRolesClaim(UUID subject, Object rolesClaim) {
		return sign(validClaims(subject.toString()).claim("roles", rolesClaim).build(), TEST_SECRET);
	}

	/** A token whose {@code sub} is the given raw value; {@code null} omits the claim. */
	public static String withRawSubject(String subject, String... roles) {
		return sign(validClaims(subject).claim("roles", List.of(roles)).build(), TEST_SECRET);
	}

	/** A token that expired an hour ago, well outside the decoder's 60-second clock skew. */
	public static String expired(UUID subject, String... roles) {
		Instant past = Instant.now().minusSeconds(3600);
		JWTClaimsSet claims = new JWTClaimsSet.Builder()
				.subject(subject.toString())
				.claim("roles", List.of(roles))
				.issueTime(Date.from(past.minusSeconds(VALIDITY_SECONDS)))
				.expirationTime(Date.from(past))
				.build();
		return sign(claims, TEST_SECRET);
	}

	/** A correctly signed token that carries no {@code exp} claim. */
	public static String withoutExpiry(UUID subject, String... roles) {
		JWTClaimsSet claims = new JWTClaimsSet.Builder()
				.subject(subject.toString())
				.claim("roles", List.of(roles))
				.issueTime(Date.from(Instant.now()))
				.build();
		return sign(claims, TEST_SECRET);
	}

	/** A valid-looking token signed with {@link #WRONG_SECRET}. */
	public static String signedWithWrongKey(UUID subject, String... roles) {
		return sign(validClaims(subject.toString()).claim("roles", List.of(roles)).build(), WRONG_SECRET);
	}

	/** An unsigned token ({@code alg: none}). */
	public static String unsigned(UUID subject, String... roles) {
		return new PlainJWT(validClaims(subject.toString()).claim("roles", List.of(roles)).build()).serialize();
	}

	/**
	 * Flips the last base64url character of the signature. The final character of a 32-byte HS256 signature
	 * encodes only 4 significant bits plus 2 zero padding bits, so replacing it with 'A' (value 0) leaves the
	 * decoded signature byte-identical whenever the original character is 'A', 'B', 'C', or 'D' (all of which
	 * share the 0000 nibble). 'E' is used in that case, because its nibble differs.
	 */
	public static String tamperSignature(String token) {
		String[] parts = token.split("\\.");
		char last = parts[2].charAt(parts[2].length() - 1);
		char flipped = (last >= 'A' && last <= 'D') ? 'E' : 'A';
		return parts[0] + "." + parts[1] + "." + parts[2].substring(0, parts[2].length() - 1) + flipped;
	}

	private static JWTClaimsSet.Builder validClaims(String subject) {
		Instant now = Instant.now();
		return new JWTClaimsSet.Builder()
				.subject(subject)
				.issueTime(Date.from(now))
				.expirationTime(Date.from(now.plusSeconds(VALIDITY_SECONDS)));
	}

	private static String sign(JWTClaimsSet claims, byte[] secret) {
		try {
			SignedJWT signedJwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
			signedJwt.sign(new MACSigner(new SecretKeySpec(secret, "HmacSHA256")));
			return signedJwt.serialize();
		} catch (JOSEException ex) {
			throw new IllegalStateException("failed to mint the test bearer token", ex);
		}
	}
}
