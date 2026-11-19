package com.serfira.shared.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.serfira.TestcontainersConfiguration;
import com.serfira.shared.observability.CorrelationId;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The browser-client CORS boundary (ADR-023) as the browser experiences it.
 *
 * <p>A preflight carries no token by definition, so it must be answered before the matcher table:
 * without the {@code cors()} filter the request falls through to {@code denyAll()} and the browser
 * reports an opaque CORS failure instead of the real status. An allowed origin must also stay readable
 * on 401, otherwise a wrong password looks like a CORS bug.
 *
 * <p>The tests use MockMvc, so they exercise the real security filter chain; fixtures are unnecessary
 * because every request here is supposed to be unauthenticated.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class CorsIT {

	private static final String ALLOWED_ORIGIN = "http://localhost:3000";
	private static final String DISALLOWED_ORIGIN = "http://evil.example";

	@Autowired
	MockMvc mockMvc;

	@Test
	void preflightFromAnAllowedOriginIsAnsweredWithoutABearerToken() throws Exception {
		mockMvc.perform(options("/api/v1/contracts")
						.header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
						.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
						.header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS,
								"authorization,content-type,idempotency-key"))
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN))
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS,
						allOf(containsString("GET"), containsString("POST"))))
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
						allOf(containsString("authorization"), containsString("content-type"),
								containsString("idempotency-key"))))
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_MAX_AGE, "1800"))
				.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS))
				.andExpect(header().string(HttpHeaders.VARY, containsString("Origin")));
	}

	@Test
	void loginPreflightSucceedsBecauseLoginIsTheFirstCall() throws Exception {
		mockMvc.perform(options("/api/v1/auth/login")
						.header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
						.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
						.header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type"))
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN));
	}

	@Test
	void preflightFromADisallowedOriginIsRejectedAndGetsNoOriginHeader() throws Exception {
		mockMvc.perform(options("/api/v1/contracts")
						.header(HttpHeaders.ORIGIN, DISALLOWED_ORIGIN)
						.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
				.andExpect(status().isForbidden())
				.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
	}

	@Test
	void preflightForAHeaderOutsideTheAllowlistIsRejected() throws Exception {
		mockMvc.perform(options("/api/v1/payments")
						.header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
						.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
						.header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "x-evil-header"))
				.andExpect(status().isForbidden())
				.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
	}

	@Test
	void unauthorizedResponseStaysReadableFromAnAllowedOrigin() throws Exception {
		mockMvc.perform(post("/api/v1/payments")
						.header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN))
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,
						containsString(CorrelationId.HEADER)))
				.andExpect(content().string(containsString("UNAUTHORIZED")));
	}

	@Test
	void actualRequestFromADisallowedOriginIsRejectedBeforeAuthorization() throws Exception {
		mockMvc.perform(get("/api/v1/contracts").header(HttpHeaders.ORIGIN, DISALLOWED_ORIGIN))
				.andExpect(status().isForbidden())
				.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
				.andExpect(content().string("Invalid CORS request"));
	}
}
