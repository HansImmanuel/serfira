package com.serfira.shared.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.serfira.shared.error.ApiError;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the wire shape of the standard envelope (TECH SPEC §2.2): {@code error} must be present
 * (as JSON null) even on success, so clients can rely on both keys always existing.
 */
class ApiResponseJsonTest {

	private final ObjectMapper mapper = new ObjectMapper();

	@Test
	void successEnvelopeAlwaysEmitsErrorKeyAsNull() throws Exception {
		String json = mapper.writeValueAsString(ApiResponse.ok("abc"));

		assertThat(json).contains("\"data\":\"abc\"").contains("\"error\":null");
	}

	@Test
	void failureEnvelopeAlwaysEmitsDataKeyAsNull() throws Exception {
		String json = mapper.writeValueAsString(ApiResponse.fail(new ApiError("NOT_FOUND", "missing")));

		assertThat(json).contains("\"data\":null")
				.contains("\"error\":{\"code\":\"NOT_FOUND\",\"message\":\"missing\"}");
	}
}
