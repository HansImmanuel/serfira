package com.serfira.shared.observability;

import java.io.IOException;

import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Assigns a correlation id to every request and makes it observable (T20, Addendum §10).
 *
 * <p>Reads {@code X-Request-Id} from the incoming request, or generates a UUID when it is absent, blank, or
 * over-long ({@link CorrelationId#normalizeOrGenerate}). The id is placed in the SLF4J MDC under
 * {@link CorrelationId#REQUEST_ID} for the whole request, so every structured log line carries it, and is
 * echoed back on the response header so a client (or an upstream proxy) can report the id it was handled
 * under. The response header is set before the chain runs, so it survives even an error dispatch that
 * replaces the body.
 *
 * <p>The MDC is always cleared in a {@code finally} block: servlet threads are pooled, so a left-over id
 * would leak onto the next, unrelated request handled by the same thread.
 *
 * <p>Registered via {@link ObservabilityConfiguration} with an order ahead of Spring Security's filter
 * chain, so authentication failures and access-denied responses are logged and tagged with a request id
 * too. The id is a correlation aid only and never influences an authorization or idempotency decision.
 */
public class CorrelationIdFilter extends OncePerRequestFilter {

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		String requestId = CorrelationId.normalizeOrGenerate(request.getHeader(CorrelationId.HEADER));
		MDC.put(CorrelationId.REQUEST_ID, requestId);
		response.setHeader(CorrelationId.HEADER, requestId);
		try {
			filterChain.doFilter(request, response);
		} finally {
			MDC.remove(CorrelationId.REQUEST_ID);
		}
	}
}
