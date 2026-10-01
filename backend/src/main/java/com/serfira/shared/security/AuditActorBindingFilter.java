package com.serfira.shared.security;

import java.io.IOException;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
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
 * actor (fail-closed, ADR-015 D5): the filter discards the authentication, so the request continues
 * anonymous, authorization answers 401, and the actor stays SYSTEM instead of being fabricated. The
 * decoder's validator already rejects such tokens, so this branch is a defensive backstop. It clears
 * the request-attribute copy of the context too, because {@code BearerTokenAuthenticationFilter}
 * saves it there and a later ERROR dispatch would otherwise restore it.
 */
public class AuditActorBindingFilter extends OncePerRequestFilter {

	private static final Logger LOGGER = LoggerFactory.getLogger(AuditActorBindingFilter.class);

	private final AuditContext auditContext;
	private final SecurityContextRepository dispatchContextRepository = new RequestAttributeSecurityContextRepository();

	public AuditActorBindingFilter(AuditContext auditContext) {
		this.auditContext = auditContext;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)) {
			filterChain.doFilter(request, response);
			return;
		}
		UUID actorId = parseUuid(jwtAuthentication.getToken().getSubject());
		if (actorId == null) {
			LOGGER.warn("Authenticated JWT has a non-UUID subject; authentication discarded for {}",
					request.getRequestURI());
			SecurityContextHolder.clearContext();
			dispatchContextRepository.saveContext(SecurityContextHolder.createEmptyContext(), request, response);
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
