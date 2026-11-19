package com.serfira.shared.security;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The CORS origin allowlist (ADR-023 D4/D5).
 *
 * <p>Spring compares {@code Origin} values literally, so an entry a browser never sends — a trailing
 * slash, a path, an uppercase host, an explicit default port — looks identical to a CORS outage from
 * the client. Parsing therefore accepts only the exact string form of an origin and fails at startup
 * with the offending entry, which is the only moment the mistake is still cheap to fix.
 *
 * <p>Wildcards are rejected rather than supported: the allowlist is an exact, small list, and
 * {@code allowCredentials=false} means Spring would otherwise accept {@code *} without complaint.
 */
final class AllowedOrigins {

	private static final String PROPERTY = "serfira.security.cors.allowed-origins";

	private AllowedOrigins() {
	}

	/**
	 * @param configured comma-separated origins from configuration; absent or blank means no origin is
	 *                   allowed, which is the safe default for a deployment with no browser client
	 * @return the validated origins, in configuration order
	 * @throws IllegalStateException if any entry is blank, a wildcard, or not a bare lowercase
	 *                               {@code http}/{@code https} origin
	 */
	static List<String> parse(String configured) {
		if (configured == null || configured.isBlank()) {
			return List.of();
		}
		List<String> origins = new ArrayList<>();
		for (String raw : configured.split(",", -1)) {
			String candidate = raw.trim();
			if (candidate.isEmpty()) {
				throw invalid("blank entry", candidate);
			}
			if (candidate.contains("*")) {
				throw new IllegalStateException(
						PROPERTY + " must not contain a wildcard: " + candidate);
			}
			origins.add(validate(candidate));
		}
		return List.copyOf(origins);
	}

	private static String validate(String candidate) {
		URI uri;
		try {
			uri = URI.create(candidate);
		} catch (IllegalArgumentException ex) {
			throw invalid("not a URI", candidate);
		}
		String scheme = uri.getScheme();
		if (scheme == null
				|| !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
			throw invalid("scheme must be http or https", candidate);
		}
		if (uri.getHost() == null || uri.getUserInfo() != null || uri.getRawQuery() != null
				|| uri.getRawFragment() != null || !uri.getRawPath().isEmpty()) {
			throw invalid("not a bare origin", candidate);
		}
		int port = uri.getPort();
		if (port != -1 && (port < 1 || port > 65535)) {
			throw invalid("port out of range", candidate);
		}
		if ((scheme.equalsIgnoreCase("http") && port == 80)
				|| (scheme.equalsIgnoreCase("https") && port == 443)) {
			throw invalid("default port is never sent by a browser", candidate);
		}
		String canonical = scheme.toLowerCase(Locale.ROOT) + "://"
				+ uri.getHost().toLowerCase(Locale.ROOT) + (port == -1 ? "" : ":" + port);
		if (!canonical.equals(candidate)) {
			throw invalid("must be lowercase and canonical", candidate);
		}
		return candidate;
	}

	private static IllegalStateException invalid(String reason, String candidate) {
		return new IllegalStateException(PROPERTY + " entry '" + candidate + "' is invalid (" + reason
				+ "); use an exact lowercase origin such as http://localhost:3000");
	}
}
