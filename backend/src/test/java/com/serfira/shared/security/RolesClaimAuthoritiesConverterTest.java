package com.serfira.shared.security;

import java.util.Collection;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@code roles} claim mapping (ADR-015 D1, D4).
 */
class RolesClaimAuthoritiesConverterTest {

	private final RolesClaimAuthoritiesConverter converter = new RolesClaimAuthoritiesConverter();

	@Test
	void oneRoleBecomesItsAuthority() {
		assertThat(authorities(List.of("FINANCE"))).containsExactly("ROLE_FINANCE");
	}

	@Test
	void severalRolesBecomeTheirAuthoritiesInClaimOrder() {
		assertThat(authorities(List.of("ADMIN_OPERASIONAL", "MANAJEMEN", "FINANCE")))
				.containsExactly("ROLE_ADMIN_OPERASIONAL", "ROLE_MANAJEMEN", "ROLE_FINANCE");
	}

	@Test
	void duplicateRolesCollapseToOneAuthority() {
		assertThat(authorities(List.of("FINANCE", "FINANCE"))).containsExactly("ROLE_FINANCE");
	}

	@Test
	void unknownValueIsIgnored() {
		assertThat(authorities(List.of("SUPERUSER", "FINANCE"))).containsExactly("ROLE_FINANCE");
	}

	@Test
	void wrongCaseIsIgnored() {
		assertThat(authorities(List.of("finance", "Admin_Operasional"))).isEmpty();
	}

	@Test
	void nonStringElementIsIgnored() {
		assertThat(authorities(List.of(42, true, "MANAJEMEN"))).containsExactly("ROLE_MANAJEMEN");
	}

	@Test
	void systemAloneGrantsNothing() {
		assertThat(authorities(List.of("SYSTEM"))).isEmpty();
	}

	@Test
	void systemAlongsideAnHttpRoleGrantsNothing() {
		assertThat(authorities(List.of("ADMIN_OPERASIONAL", "SYSTEM"))).isEmpty();
	}

	@Test
	void wrongCaseSystemIsJustAnUnknownValue() {
		assertThat(authorities(List.of("system", "FINANCE"))).containsExactly("ROLE_FINANCE");
	}

	@Test
	void stringValuedClaimGrantsNothing() {
		assertThat(authorities("ADMIN_OPERASIONAL")).isEmpty();
	}

	@Test
	void missingClaimGrantsNothing() {
		Jwt jwt = Jwt.withTokenValue("t").header("alg", "HS256").subject("s").build();
		assertThat(converter.convert(jwt)).isEmpty();
	}

	@Test
	void resultIsUnmodifiable() {
		Collection<GrantedAuthority> result = converter.convert(jwtWithRoles(List.of("FINANCE")));
		assertThat(result).isUnmodifiable();
	}

	private List<String> authorities(Object rolesClaim) {
		return converter.convert(jwtWithRoles(rolesClaim)).stream()
				.map(GrantedAuthority::getAuthority)
				.toList();
	}

	private static Jwt jwtWithRoles(Object rolesClaim) {
		return Jwt.withTokenValue("t").header("alg", "HS256").subject("s").claim("roles", rolesClaim).build();
	}
}
