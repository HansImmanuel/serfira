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
 * <p>Reads {@code X-Request-Id} from the incoming request, or generates a UUID when it is absent,
 * blank, or invalid. The resolved id is stored as a request attribute so subsequent dispatches,
 * including ERROR dispatches, reuse the same id.
 *
 * <p>The id is placed in the SLF4J MDC under {@link CorrelationId#REQUEST_ID} while the filter
 * chain runs and is echoed back on the response header.
 *
 * <p>The MDC is always restored in a {@code finally} block because servlet threads are pooled.
 *
 * <p>Registered ahead of Spring Security's filter chain, so authentication failures and
 * access-denied responses are correlated too.
 */
public class CorrelationIdFilter extends OncePerRequestFilter {

    private static final String REQUEST_ID_ATTRIBUTE =
            CorrelationIdFilter.class.getName() + ".REQUEST_ID";

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String requestId = (String) request.getAttribute(REQUEST_ID_ATTRIBUTE);

        if (requestId == null) {
            requestId = CorrelationId.normalizeOrGenerate(
                    request.getHeader(CorrelationId.HEADER)
            );
            request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
        }

        response.setHeader(CorrelationId.HEADER, requestId);

        String previousRequestId = MDC.get(CorrelationId.REQUEST_ID);

        try {
            MDC.put(CorrelationId.REQUEST_ID, requestId);
            filterChain.doFilter(request, response);
        } finally {
            if (previousRequestId == null) {
                MDC.remove(CorrelationId.REQUEST_ID);
            } else {
                MDC.put(CorrelationId.REQUEST_ID, previousRequestId);
            }
        }
    }
}
