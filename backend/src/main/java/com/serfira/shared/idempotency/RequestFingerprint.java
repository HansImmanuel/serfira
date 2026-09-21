package com.serfira.shared.idempotency;

import com.serfira.shared.security.PiiSecuritySupport;

import java.util.Objects;

/**
 * Fingerprint of a canonicalized request body, used as {@code idempotency_keys.request_hash}.
 *
 * <p>02_TECH_SPEC.md §2.5 asks for "SHA-256 of the canonical JSON request". The request payloads
 * that need retry safety contain PII (NIK/phone), and a plain unkeyed digest of PII is
 * dictionary-attackable, so this implementation uses the project's existing <b>keyed</b>
 * HMAC-SHA-256 primitive (ADR-004, {@link PiiSecuritySupport#hmacHex}) instead. Equality semantics
 * are identical, the value is not reversible, and it is host-bound — the refinement is recorded in
 * ADR-007.
 */
public final class RequestFingerprint {

	private RequestFingerprint() {
	}

	/** Keyed digest of a canonical JSON payload. The payload itself is never persisted or logged. */
	public static String of(String canonicalJson) {
		Objects.requireNonNull(canonicalJson, "canonicalJson");
		return PiiSecuritySupport.hmacHex(canonicalJson);
	}
}