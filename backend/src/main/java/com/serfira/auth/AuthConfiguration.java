package com.serfira.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Auth-module configuration (T21a, ADR-022).
 *
 * <p>Declares the {@link PasswordEncoder} bean for interactive users. It lives in the {@code auth}
 * module rather than {@code ResourceServerSecurityConfiguration} so the security filter-chain
 * configuration stays focused on token validation and authorization.
 *
 * <p>Uses Spring Security's {@link Argon2PasswordEncoder} with the v5.8 defaults
 * ({@code m=16384 KiB, t=2, p=1}, 16-byte salt, 32-byte hash). BouncyCastle
 * ({@code bcprov-jdk18on}) is on the classpath for the Argon2 KDF (ADR-022). The seeded demo
 * hashes in {@code V99__demo_seed.sql} are produced with the same parameters.
 *
 * <p>The {@code Clock} bean is provided by {@code ClockConfiguration} in
 * {@code shared.clock} and is not redeclared here.
 */
@Configuration
public class AuthConfiguration {

	@Bean
	PasswordEncoder passwordEncoder() {
		return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
	}
}
