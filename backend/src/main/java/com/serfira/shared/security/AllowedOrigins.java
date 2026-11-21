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
 *
 * <p>IPv6 literals are compared against their browser-canonical serialization, not merely lowercased:
 * {@link URI#getHost()} preserves the spelling it was given, so {@code [0:0:0:0:0:0:0:1]} would pass a
 * lowercase-only check even though a browser always sends {@code [::1]} (ADR-023 D5).
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
				+ canonicalHost(uri.getHost(), candidate) + (port == -1 ? "" : ":" + port);
		if (!canonical.equals(candidate)) {
			throw invalid("must be lowercase and canonical", candidate);
		}
		return candidate;
	}

	private static String canonicalHost(String host, String candidate) {
		if (!host.startsWith("[")) {
			return host.toLowerCase(Locale.ROOT);
		}
		if (!host.endsWith("]")) {
			throw invalid("not a bare origin", candidate);
		}
		return "[" + canonicalIpv6(host.substring(1, host.length() - 1), candidate) + "]";
	}

	/**
	 * Serializes an IPv6 literal the way a browser does (WHATWG URL, "IPv6 serializer"): lowercase hex,
	 * no leading zeroes, and the first longest run of zero pieces compressed to {@code ::}. No DNS lookup
	 * or {@link java.net.InetAddress} parsing is involved, so startup stays network-independent.
	 */
	private static String canonicalIpv6(String literal, String candidate) {
		if (literal.indexOf('%') >= 0) {
			throw invalid("IPv6 zone identifiers are not part of an origin", candidate);
		}
		String[] halves = literal.split("::", -1);
		if (halves.length > 2) {
			throw invalid("not a valid IPv6 address", candidate);
		}
		List<Integer> head = parseIpv6Groups(halves[0], candidate);
		List<Integer> tail = halves.length == 2 ? parseIpv6Groups(halves[1], candidate) : List.of();
		int[] pieces = new int[8];
		if (halves.length == 1) {
			if (head.size() != pieces.length) {
				throw invalid("not a valid IPv6 address", candidate);
			}
			for (int i = 0; i < pieces.length; i++) {
				pieces[i] = head.get(i);
			}
		} else {
			// "::" must stand for at least one zero piece; a browser would not serialize an empty run.
			if (head.size() + tail.size() > pieces.length - 1) {
				throw invalid("not a valid IPv6 address", candidate);
			}
			for (int i = 0; i < head.size(); i++) {
				pieces[i] = head.get(i);
			}
			for (int i = 0; i < tail.size(); i++) {
				pieces[pieces.length - tail.size() + i] = tail.get(i);
			}
		}
		return serializeIpv6(pieces);
	}

	private static List<Integer> parseIpv6Groups(String part, String candidate) {
		if (part.isEmpty()) {
			return List.of();
		}
		String[] groups = part.split(":", -1);
		List<Integer> pieces = new ArrayList<>(groups.length);
		for (String group : groups) {
			if (group.indexOf('.') >= 0) {
				throw invalid(
						"IPv6 literal must be browser-canonical (an embedded IPv4 is written as hex)",
						candidate);
			}
			if (group.isEmpty() || group.length() > 4 || !isHex(group)) {
				throw invalid("not a valid IPv6 address", candidate);
			}
			pieces.add(Integer.parseInt(group, 16));
		}
		return pieces;
	}

	private static boolean isHex(String value) {
		for (int i = 0; i < value.length(); i++) {
			char character = value.charAt(i);
			boolean hex = (character >= '0' && character <= '9')
					|| (character >= 'a' && character <= 'f')
					|| (character >= 'A' && character <= 'F');
			if (!hex) {
				return false;
			}
		}
		return true;
	}

	private static String serializeIpv6(int[] pieces) {
		int runStart = -1;
		int runLength = 1;
		for (int i = 0; i < pieces.length; ) {
			if (pieces[i] != 0) {
				i++;
				continue;
			}
			int start = i;
			while (i < pieces.length && pieces[i] == 0) {
				i++;
			}
			int length = i - start;
			if (length > runLength) {
				runStart = start;
				runLength = length;
			}
		}
		StringBuilder serialized = new StringBuilder();
		for (int i = 0; i < pieces.length; i++) {
			if (i == runStart) {
				serialized.append("::");
				i += runLength - 1;
				continue;
			}
			if (!serialized.isEmpty() && serialized.charAt(serialized.length() - 1) != ':') {
				serialized.append(':');
			}
			serialized.append(Integer.toHexString(pieces[i]));
		}
		return serialized.toString();
	}

	private static IllegalStateException invalid(String reason, String candidate) {
		return new IllegalStateException(PROPERTY + " entry '" + candidate + "' is invalid (" + reason
				+ "); use an exact lowercase origin such as http://localhost:3000");
	}
}
