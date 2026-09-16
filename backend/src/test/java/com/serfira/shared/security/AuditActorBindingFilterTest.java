package com.serfira.shared.security;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.serfira.shared.audit.AuditContext;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the JWT subject → {@link AuditContext} binding performed by
 * {@link AuditActorBindingFilter} (review CRIT-1 / Addendum §3.3 attribution rule).
 */
class AuditActorBindingFilterTest {

	private static final UUID ACTOR = UUID.randomUUID();

	private final AuditContext auditContext = new AuditContext();
	private final AuditActorBindingFilter filter = new AuditActorBindingFilter(auditContext);

	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
		auditContext.resetToSystem();
	}

	@Test
	void bindsJwtSubjectAsActorForTheDurationOfTheRequest() throws ServletException, IOException {
		authenticateWithSubject(ACTOR.toString());

		MockHttpServletRequest request = new MockHttpServletRequest();
		MockHttpServletResponse response = new MockHttpServletResponse();
		filter.doFilter(request, response, (req, res) ->
				assertThat(auditContext.actorId()).isEqualTo(ACTOR));

		// The actor is restored (to SYSTEM) once the request completes.
		assertThat(auditContext.actorId()).isEqualTo(AuditContext.SYSTEM_USER_ID);
	}

	@Test
	void requestWithoutAuthenticationKeepsSystemActor() throws ServletException, IOException {
		SecurityContextHolder.clearContext();

		MockHttpServletRequest request = new MockHttpServletRequest();
		MockHttpServletResponse response = new MockHttpServletResponse();
		filter.doFilter(request, response, (req, res) ->
				assertThat(auditContext.actorId()).isEqualTo(AuditContext.SYSTEM_USER_ID));

		assertThat(auditContext.actorId()).isEqualTo(AuditContext.SYSTEM_USER_ID);
	}

	@Test
	void nonUuidSubjectIsNotFabricatedIntoAnActor() throws ServletException, IOException {
		authenticateWithSubject("not-a-uuid");

		MockHttpServletRequest request = new MockHttpServletRequest();
		MockHttpServletResponse response = new MockHttpServletResponse();
		filter.doFilter(request, response, (req, res) ->
				assertThat(auditContext.actorId()).isEqualTo(AuditContext.SYSTEM_USER_ID));

		assertThat(auditContext.actorId()).isEqualTo(AuditContext.SYSTEM_USER_ID);
	}

	private void authenticateWithSubject(String subject) {
		Jwt jwt = Jwt.withTokenValue("test-token")
				.header("alg", "HS256")
				.subject(subject)
				.build();
		SecurityContextHolder.getContext()
				.setAuthentication(new JwtAuthenticationToken(jwt));
	}
}
