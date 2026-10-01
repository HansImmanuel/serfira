package com.serfira.shared.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.serfira.TestcontainersConfiguration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Smoke test for the public OpenAPI surface (T23, review CR-03). springdoc inspects the running MVC context,
 * so a runtime incompatibility with the Boot line only shows up when the document is actually generated. It
 * also pins the ADR-005 public paths: both requests carry no token.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiSmokeIT {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	ObjectMapper objectMapper;

	@Test
	void apiDocsArePublicAndListTheBusinessEndpoints() throws Exception {
		MvcResult result = mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andReturn();

		JsonNode paths = objectMapper.readTree(result.getResponse().getContentAsString()).get("paths");
		assertThat(paths).as("OpenAPI document has a paths object").isNotNull();
		assertThat(paths.has("/api/v1/payments")).isTrue();
		assertThat(paths.has("/api/v1/contracts")).isTrue();
		// T7: every operation documents its 403 and the roles allowed (convention: list every error code).
		assertThat(paths.at("/~1api~1v1~1payments/post/responses/403/description").asText())
				.contains("ADMIN_OPERASIONAL");
		assertThat(paths.at("/~1api~1v1~1contracts/get/responses/403/description").asText())
				.contains("FINANCE").contains("MANAJEMEN");
	}

	@Test
	void swaggerUiEntryPointResolvesWithoutToken() throws Exception {
		int statusCode = mockMvc.perform(get("/swagger-ui.html"))
				.andReturn()
				.getResponse()
				.getStatus();

		// springdoc answers the entry point with a redirect to /swagger-ui/index.html.
		assertThat(statusCode).isBetween(200, 399);
	}

	@Test
	void swaggerUiIndexIsServedWithoutToken() throws Exception {
		mockMvc.perform(get("/swagger-ui/index.html"))
				.andExpect(status().isOk());
	}
}
