package com.serfira.shared.security;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.serfira.TestcontainersConfiguration;
import com.serfira.support.TestJwts;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Container error dispatches under the default-deny matcher table (ADR-015 D3).
 *
 * <p>Spring Security authorizes every dispatch, so without the {@code DispatcherType.ERROR} rule a container
 * error forward to {@code /error} would be denied and its status replaced by 401/403. MockMvc never performs
 * error dispatches, so this suite runs a real servlet container. A URL rejected by Spring Security's
 * {@code StrictHttpFirewall} (a {@code ;} in the path) is the deterministic trigger: the rejection handler
 * calls {@code sendError(400)}, and the container then dispatches to {@code /error}.
 *
 * <p>The ERROR rule must not make {@code /error} public: a client request for it is a REQUEST dispatch and
 * still falls through to {@code denyAll}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ErrorDispatchSecurityIT {

	private static final String FIREWALL_REJECTED_PATH = "/api/v1/contracts;x=1";

	private final HttpClient client = HttpClient.newHttpClient();

	@Value("${local.server.port}")
	int port;

	@Autowired
	JdbcTemplate jdbc;

	private UUID actorId;

	/**
	 * The active-user validator (T21a) rejects a token whose {@code sub} is not a real, active
	 * {@code app_user}; these tests need a token that authenticates so the matcher table runs.
	 */
	@BeforeEach
	void seedActiveActor() {
		actorId = jdbc.queryForObject("""
				insert into app_user (username, password_hash, full_name, role, is_active, created_at, updated_at)
					values (?, 'test-hash', 'Error Dispatch IT Actor', 'ADMIN_OPERASIONAL', TRUE,
						clock_timestamp(), clock_timestamp())
					returning id""", UUID.class, "error-dispatch-actor-" + UUID.randomUUID());
	}

	@AfterEach
	void removeActor() {
		jdbc.update("delete from app_user where id = ?", actorId);
	}

	@Test
	void firewallRejectionKeepsItsStatusWithoutAToken() throws Exception {
		HttpResponse<String> response = send(FIREWALL_REJECTED_PATH, null);

		assertThat(response.statusCode()).as("body: %s", response.body()).isEqualTo(400);
		assertNoInternalsLeak(response);
	}

	@Test
	void firewallRejectionKeepsItsStatusWithAValidToken() throws Exception {
		String token = TestJwts.forRoles(actorId, AppRole.ADMIN_OPERASIONAL.name());

		HttpResponse<String> response = send(FIREWALL_REJECTED_PATH, token);

		assertThat(response.statusCode()).as("body: %s", response.body()).isEqualTo(400);
		assertNoInternalsLeak(response);
	}

	@Test
	void directRequestForTheErrorPathWithoutATokenIsUnauthorized() throws Exception {
		HttpResponse<String> response = send("/error", null);

		assertThat(response.statusCode()).isEqualTo(401);
		assertThat(response.body()).contains("\"UNAUTHORIZED\"");
	}

	@Test
	void directRequestForTheErrorPathWithATokenIsForbidden() throws Exception {
		String token = TestJwts.forRoles(actorId, AppRole.ADMIN_OPERASIONAL.name());

		HttpResponse<String> response = send("/error", token);

		assertThat(response.statusCode()).isEqualTo(403);
		assertThat(response.body()).contains("\"FORBIDDEN\"");
	}

	private HttpResponse<String> send(String path, String bearer) throws IOException, InterruptedException {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
		if (bearer != null) {
			request.header("Authorization", "Bearer " + bearer);
		}
		return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}

	private static void assertNoInternalsLeak(HttpResponse<String> response) {
		assertThat(response.body()).doesNotContain("trace").doesNotContain("Exception");
	}
}
