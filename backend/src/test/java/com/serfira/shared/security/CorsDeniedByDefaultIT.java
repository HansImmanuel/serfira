package com.serfira.shared.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import com.serfira.TestcontainersConfiguration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The absent-configuration default of the CORS allowlist (ADR-023 D4): a deployment that has not named
 * an origin has no browser client, so no origin is allowed. Kept separate from {@link CorsIT} because
 * the default is a context-wide property and the allowed-origin suite needs the value set.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "serfira.security.cors.allowed-origins=")
@AutoConfigureMockMvc
class CorsDeniedByDefaultIT {

	private static final String ORIGIN = "http://localhost:3000";

	@Autowired
	MockMvc mockMvc;

	@Test
	void noConfiguredOriginIsAllowed() throws Exception {
		mockMvc.perform(options("/api/v1/contracts")
						.header(HttpHeaders.ORIGIN, ORIGIN)
						.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
				.andExpect(status().isForbidden())
				.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
	}
}
