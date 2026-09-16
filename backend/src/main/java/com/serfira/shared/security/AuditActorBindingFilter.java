package com.serfira.shared.security;

import java.io.IOException;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import com.serfira.shared.audit.AuditContext;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Binds the authenticated JWT subject to the {@link AuditContext} for the duration of the request
 * (review CRIT-1; Addendum §3.3): {@code created_by}/{@code updated_by} of every user-facing write
 * is the authenticated principal's {@code app_user} id, never the seeded SYSTEM user.
 *
 * <p>Runs after {@code BearerTokenAuthenticationFilter}, so the security context is already
 * populated when this filter executes. Requests that reach the chain without authentication
 * (public paths only) keep the SYSTEM actor — no business mutation is reachable for them.
 *
 * <p>An authenticated principal whose {@code sub} claim is not a UUID is treated as an invalid
 * actor (fail-closed): the request proceeds unauthenticated so authorization rejects it, and the
 * actor stays SYSTEM instead of being fabricated.
 */
public class AuditActorBindingFilter extends OncePerRequestFilter {

	private static final Logger LOGGER = LoggerFactory.getLogger(AuditActorBindingFilter.class);

	private final AuditContext auditContext;

	public AuditActorBindingFilter(AuditContext auditContext) {
		this.auditContext = auditContext;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		UUID actorId = null;
		if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
			actorId = parseUuid(jwtAuthentication.getToken().getSubject());
			if (actorId == null) {
				LOGGER.debug("JWT subject is not a UUID; audit actor remains SYSTEM for this request");
			}
		}
		if (actorId == null) {
			filterChain.doFilter(request, response);
			return;
		}
		try {
			auditContext.runAs(actorId, () -> {
				try {
					filterChain.doFilter(request, response);
				} catch (ServletException | IOException ex) {
					throw new WrappedFilterException(ex);
				}
			});
		} catch (WrappedFilterException ex) {
			switch (ex.getCause()) {
				case ServletException servletException -> throw servletException;
				case IOException ioException -> throw ioException;
				default -> throw ex;
			}
		}
	}

	private UUID parseUuid(String subject) {
		if (subject == null) {
			return null;
		}
		try {
			return UUID.fromString(subject);
		} catch (IllegalArgumentException ex) {
			return null;
		}
	}

	/** Restores checked servlet exception types across the lambda boundary of {@code runAs}. */
	private static final class WrappedFilterException extends RuntimeException {

		private WrappedFilterException(Throwable cause) {
			super(cause);
		}
	}
}
