package com.serfira.shared.security;

/**
 * The envelope format new PII values are written in (ADR-025 D7). Reads always accept both.
 *
 * <ul>
 *   <li>{@link #V1} — {@code v1:<base64>}: no key id, read under the legacy key. Every pre-T28a version reads it,
 *       so it is the default: deploying this version (or rolling back from it) changes nothing on disk.</li>
 *   <li>{@link #V2} — {@code v2:<kid>:<base64>}: names its key, so it can be written under any active key.
 *       Pre-T28a versions cannot read it; it is switched on once no such version runs or can be rolled back to.</li>
 * </ul>
 */
public enum PiiEnvelopeVersion {
	V1,
	V2
}
