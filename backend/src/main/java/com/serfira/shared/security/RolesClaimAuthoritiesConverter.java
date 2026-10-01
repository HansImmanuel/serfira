package com.serfira.shared.security;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Maps the JWT {@code roles} claim to Spring authorities (ADR-015 D1, D4). It replaces the default
 * {@code scope}/{@code scp} → {@code SCOPE_*} mapping, which Serfira does not use.
 *
 * <ul>
 * <li>The claim must be a JSON array. Anything else (absent, a string, an object) yields no authorities, so
 * the token authenticates but every endpoint answers 403.</li>
 * <li>Each string element naming an HTTP {@link AppRole} exactly (case-sensitive) becomes
 * {@code ROLE_<name>}. Unknown, wrongly cased, and non-string elements are ignored.</li>
 * <li>If any element is {@code SYSTEM}, the whole token gets no authorities, even alongside another role.
 * {@code SYSTEM} only exists for in-process jobs, so such a token should never have been minted.</li>
 * </ul>
 */
public final class RolesClaimAuthoritiesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

	/** Claim name agreed for T21's token issuer (ADR-015 D1). */
	public static final String ROLES_CLAIM = "roles";

	private static final Logger LOGGER = LoggerFactory.getLogger(RolesClaimAuthoritiesConverter.class);

	@Override
	public Collection<GrantedAuthority> convert(Jwt jwt) {
		Object claim = jwt.getClaim(ROLES_CLAIM);
		if (!(claim instanceof Collection<?> elements)) {
			return List.of();
		}
		Set<GrantedAuthority> authorities = new LinkedHashSet<>();
		for (Object element : elements) {
			if (!(element instanceof String claimValue)) {
				continue;
			}
			Optional<AppRole> role = AppRole.fromClaimValue(claimValue);
			if (role.isEmpty()) {
				continue;
			}
			if (!role.get().isHttpRole()) {
				// The subject is an app_user id (a UUID once the decoder validated it), never PII.
				LOGGER.warn("JWT for subject {} carries the {} role; all authorities withheld",
						jwt.getSubject(), role.get());
				return List.of();
			}
			authorities.add(new SimpleGrantedAuthority(role.get().authority()));
		}
		return List.copyOf(authorities);
	}
}
