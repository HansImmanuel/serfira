package com.serfira.shared.security;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * Startup validation of the browser-origin allowlist (ADR-023 D4/D5).
 *
 * <p>Spring compares {@code Origin} values literally, so a configured entry a browser never sends looks
 * exactly like a CORS outage from the client. These tests pin the fail-fast contract: only the exact
 * string form of an origin is accepted, and nothing dangerous reaches {@code CorsConfiguration}.
 */
class AllowedOriginsTest {

	@Test
	void absentOrBlankConfigurationAllowsNoOrigin() {
		assertThat(AllowedOrigins.parse(null)).isEqualTo(List.of());
		assertThat(AllowedOrigins.parse("")).isEqualTo(List.of());
		assertThat(AllowedOrigins.parse("   ")).isEqualTo(List.of());
	}

	@Test
	void parsesCommaSeparatedOriginsAndTrimsSurroundingWhitespace() {
		assertThat(AllowedOrigins.parse(" http://localhost:3000 ,https://app.example.com"))
				.containsExactly("http://localhost:3000", "https://app.example.com");
	}

	@Test
	void acceptsHttpsWithoutAPortAndAnIpv6LoopbackOrigin() {
		assertThat(AllowedOrigins.parse("https://app.example.com,http://[::1]:3000"))
				.containsExactly("https://app.example.com", "http://[::1]:3000");
	}

	@Test
	void acceptsBrowserCanonicalIpv6Origins() {
		assertThat(AllowedOrigins.parse(
				"http://[::]:3000,https://[2001:db8::1],http://[::ffff:7f00:1]:3000"))
				.containsExactly(
						"http://[::]:3000", "https://[2001:db8::1]", "http://[::ffff:7f00:1]:3000");
	}

	@Test
	void rejectsNonCanonicalIpv6Representations() {
		// A browser serializes the loopback as [::1]; the uncompressed spelling never appears in Origin.
		assertThatIllegalStateException()
				.isThrownBy(() -> AllowedOrigins.parse("http://[0:0:0:0:0:0:0:1]:3000"))
				.withMessageContaining("canonical");
		// Leading zeroes and uppercase hex are normalized away by the URL parser.
		assertThatIllegalStateException()
				.isThrownBy(() -> AllowedOrigins.parse("http://[2001:0db8::1]:3000"))
				.withMessageContaining("canonical");
		assertThatIllegalStateException()
				.isThrownBy(() -> AllowedOrigins.parse("http://[2001:DB8::1]:3000"))
				.withMessageContaining("canonical");
		// Only the longest zero run is compressed, and the first one wins on a tie.
		assertThatIllegalStateException()
				.isThrownBy(() -> AllowedOrigins.parse("http://[1:0:0:2:0:0:0:3]:3000"))
				.withMessageContaining("canonical");
		// Browsers serialize an IPv4-mapped address as hex pieces, never as a dotted quad.
		assertThatIllegalStateException()
				.isThrownBy(() -> AllowedOrigins.parse("http://[::ffff:192.0.2.1]:3000"))
				.withMessageContaining("canonical");
	}

	@Test
	void rejectsMalformedIpv6Literals() {
		assertThatIllegalStateException()
				.isThrownBy(() -> AllowedOrigins.parse("http://[1::2::3]:3000"))
				.withMessageContaining("http://[1::2::3]:3000");
		assertThatIllegalStateException()
				.isThrownBy(() -> AllowedOrigins.parse("http://[12345::1]:3000"))
				.withMessageContaining("http://[12345::1]:3000");
		assertThatIllegalStateException()
				.isThrownBy(() -> AllowedOrigins.parse("http://[1:2:3:4:5:6:7]:3000"))
				.withMessageContaining("http://[1:2:3:4:5:6:7]:3000");
		assertThatIllegalStateException()
				.isThrownBy(() -> AllowedOrigins.parse("http://[fe80::1%25eth0]:3000"))
				.withMessageContaining("http://[fe80::1%25eth0]:3000");
	}

	@Test
	void rejectsWildcards() {
		assertThatIllegalStateException()
				.isThrownBy(() -> AllowedOrigins.parse("*"))
				.withMessageContaining("wildcard");
		assertThatIllegalStateException()
				.isThrownBy(() -> AllowedOrigins.parse("https://*.example.com"))
				.withMessageContaining("wildcard");
	}

	@Test
	void rejectsAValueThatIsNotABareOrigin() {
		assertThatIllegalStateException()
				.isThrownBy(() -> AllowedOrigins.parse("http://localhost:3000/"))
				.withMessageContaining("http://localhost:3000/");
		assertThatIllegalStateException()
				.isThrownBy(() -> AllowedOrigins.parse("http://localhost:3000/app"))
				.withMessageContaining("http://localhost:3000/app");
		assertThatIllegalStateException()
				.isThrownBy(() -> AllowedOrigins.parse("http://localhost:3000?debug=1"))
				.withMessageContaining("http://localhost:3000?debug=1");
		assertThatIllegalStateException()
				.isThrownBy(() -> AllowedOrigins.parse("localhost:3000"))
				.withMessageContaining("localhost:3000");
	}

	@Test
	void rejectsANonHttpSchemeAndANonCanonicalHost() {
		assertThatIllegalStateException()
				.isThrownBy(() -> AllowedOrigins.parse("ftp://files.example.com"))
				.withMessageContaining("ftp://files.example.com");
		assertThatIllegalStateException()
				.isThrownBy(() -> AllowedOrigins.parse("HTTP://LocalHost:3000"))
				.withMessageContaining("HTTP://LocalHost:3000");
	}

	@Test
	void rejectsTheDefaultPortBecauseABrowserNeverSendsIt() {
		assertThatIllegalStateException()
				.isThrownBy(() -> AllowedOrigins.parse("http://localhost:80"))
				.withMessageContaining("default port");
		assertThatIllegalStateException()
				.isThrownBy(() -> AllowedOrigins.parse("https://app.example.com:443"))
				.withMessageContaining("default port");
	}

	@Test
	void rejectsBlankEntriesAndOutOfRangePorts() {
		assertThatIllegalStateException()
				.isThrownBy(() -> AllowedOrigins.parse("http://localhost:3000,,https://app.example.com"))
				.withMessageContaining("blank");
		assertThatIllegalStateException()
				.isThrownBy(() -> AllowedOrigins.parse("http://localhost:99999"))
				.withMessageContaining("http://localhost:99999");
	}
}
