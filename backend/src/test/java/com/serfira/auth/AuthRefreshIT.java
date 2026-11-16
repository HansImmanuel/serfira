package com.serfira.auth;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.serfira.TestcontainersConfiguration;
import com.serfira.auth.application.AuthService;
import com.serfira.auth.application.InvalidRefreshTokenException;
import com.serfira.auth.application.LoginRequest;
import com.serfira.auth.application.RefreshTokenRequest;
import com.serfira.auth.application.TokenPairResponse;
import com.serfira.auth.infrastructure.RefreshTokenRepository;
import com.serfira.shared.clock.FixedClock;
import com.serfira.shared.security.AppRole;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Refresh-token rotation for T21b (Addendum §3.3, ADR-022 addendum D11).
 *
 * <p>Covers the acceptance criteria that need a database: a refresh revokes the presented row, links
 * it to its replacement through {@code replaced_by_id}, and mints a new pair; a replayed, expired,
 * revoked or deactivated-account token mints nothing; and two concurrent refreshes of the same token
 * produce exactly one winner, because the row is retired by a conditional update rather than a
 * read-modify-write.
 *
 * <p>The concurrency case calls {@link AuthService} from two threads instead of MockMvc: the point is
 * the transaction and row lock, not the HTTP layer, and a direct call keeps the race deterministic
 * (both threads are released by one barrier). Everyone else goes over HTTP so the wiring is covered
 * too.
 */
@Import({TestcontainersConfiguration.class, AuthClockTestConfiguration.class,
		AuthRefreshIT.LookupBarrierConfiguration.class})
@SpringBootTest
@AutoConfigureMockMvc
class AuthRefreshIT {

	private static final UUID SYSTEM_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

	private static final String ADMIN = AppRole.ADMIN_OPERASIONAL.name();
	private static final String USERNAME = "refresh-it-admin";
	private static final String PASSWORD = "Admin123!";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	ObjectMapper objectMapper;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	AuthService authService;

	@Autowired
	RefreshTokenLookupBarrier lookupBarrier;

	@Autowired
	FixedClock authFixedClock;

	private OffsetDateTime testNow;

	@BeforeEach
	void resetDatabase() {
		truncateDomainTables();
		// Keep the seeded SYSTEM principal; clear only IT-created users.
		jdbc.update("delete from app_user where id <> ?", SYSTEM_USER_ID);
		testNow = OffsetDateTime.now(ZoneOffset.ofHours(7)).truncatedTo(ChronoUnit.SECONDS);
		authFixedClock.set(testNow);
	}

	@AfterEach
	void leaveCleanSharedDatabase() {
		truncateDomainTables();
	}

	@Test
	void refreshRotatesThePairAndLinksTheReplacement() throws Exception {
		UUID userId = seedUser(USERNAME, ADMIN, true);
		TokenPairResponse first = login();

		MvcResult result = refresh(first.refreshToken());

		assertThat(result.getResponse().getStatus())
				.as("response body: %s", result.getResponse().getContentAsString())
				.isEqualTo(200);
		JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
		String newRefreshToken = data.get("refresh_token").asText();
		assertThat(newRefreshToken).isNotBlank().isNotEqualTo(first.refreshToken());
		assertThat(data.get("access_token").asText()).isNotBlank();

		UUID replacementId = jdbc.queryForObject(
				"select id from refresh_token where token_hash = ?", UUID.class, sha256Hex(newRefreshToken));
		OffsetDateTime revokedAt = jdbc.queryForObject(
				"select revoked_at from refresh_token where token_hash = ?", OffsetDateTime.class,
				sha256Hex(first.refreshToken()));
		assertThat(revokedAt.toInstant()).isEqualTo(testNow.toInstant());
		assertThat(jdbc.queryForObject(
				"select replaced_by_id from refresh_token where token_hash = ?", UUID.class,
				sha256Hex(first.refreshToken()))).isEqualTo(replacementId);
		assertThat(jdbc.queryForObject(
				"select revoked_at from refresh_token where id = ?", OffsetDateTime.class, replacementId)).isNull();
		// The replacement carries a fresh 7-day expiry: the window slides with each rotation.
		assertThat(jdbc.queryForObject(
				"select expires_at from refresh_token where id = ?", OffsetDateTime.class, replacementId).toInstant())
				.isEqualTo(testNow.plusDays(7).toInstant());
		assertThat(jdbc.queryForObject(
				"select user_id from refresh_token where id = ?", UUID.class, replacementId)).isEqualTo(userId);
	}

	@Test
	void replayedRefreshTokenIsRejectedAndMintsNothing() throws Exception {
		seedUser(USERNAME, ADMIN, true);
		TokenPairResponse first = login();
		assertThat(refresh(first.refreshToken()).getResponse().getStatus()).isEqualTo(200);

		MvcResult replay = refresh(first.refreshToken());

		assertThat(replay.getResponse().getStatus()).isEqualTo(401);
		assertEnvelopeError(replay, "UNAUTHORIZED");
		// Only the login row and the one replacement from the successful rotation exist.
		assertThat(jdbc.queryForObject("select count(*) from refresh_token", Long.class)).isEqualTo(2);
	}

	@Test
	void unknownRefreshTokenIsRejected() throws Exception {
		seedUser(USERNAME, ADMIN, true);

		MvcResult result = refresh(UUID.randomUUID().toString());

		assertThat(result.getResponse().getStatus()).isEqualTo(401);
		assertEnvelopeError(result, "UNAUTHORIZED");
	}

	@Test
	void expiredRefreshTokenIsRejected() throws Exception {
		seedUser(USERNAME, ADMIN, true);
		TokenPairResponse issued = login();

		authFixedClock.advanceBy(Duration.ofDays(7).plusSeconds(1));
		MvcResult result = refresh(issued.refreshToken());

		assertThat(result.getResponse().getStatus()).isEqualTo(401);
		assertEnvelopeError(result, "UNAUTHORIZED");
		assertThat(jdbc.queryForObject("select count(*) from refresh_token", Long.class)).isEqualTo(1);
	}

	@Test
	void refreshForDeactivatedUserIsRejected() throws Exception {
		UUID userId = seedUser(USERNAME, ADMIN, true);
		TokenPairResponse issued = login();

		jdbc.update("update app_user set is_active = false where id = ?", userId);
		MvcResult result = refresh(issued.refreshToken());

		assertThat(result.getResponse().getStatus()).isEqualTo(401);
		assertEnvelopeError(result, "UNAUTHORIZED");
		// The token was not rotated away: revocation only happens on a successful rotation.
		assertThat(jdbc.queryForObject(
				"select revoked_at from refresh_token where user_id = ?", OffsetDateTime.class, userId)).isNull();
	}

	@Test
	void twoConcurrentRefreshesYieldExactlyOneWinner() throws Exception {
		seedUser(USERNAME, ADMIN, true);
		TokenPairResponse issued = login();
		RefreshTokenRequest request = new RefreshTokenRequest(issued.refreshToken());

		// Release both attempts only after both have read the original unrevoked row. A barrier
		// before refresh() would let one request finish its whole transaction first, leaving the
		// conditional update untested.
		lookupBarrier.arm(2);

		Callable<TokenPairResponse> attempt = () -> {
			try {
				return authService.refresh(request);
			} catch (InvalidRefreshTokenException ex) {
				return null;
			}
		};
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			List<Future<TokenPairResponse>> outcomes = pool.invokeAll(List.of(attempt, attempt));

			List<TokenPairResponse> succeeded = outcomes.stream()
					.map(AuthRefreshIT::resultOf)
					.filter(Objects::nonNull)
					.toList();
			assertThat(succeeded).hasSize(1);
			// Proves both attempts passed through the read barrier instead of running sequentially.
			assertThat(lookupBarrier.arrivedLookups()).isEqualTo(2);
		} finally {
			lookupBarrier.disarm();
			pool.shutdownNow();
		}

		// Login row + exactly one replacement: the loser's insert rolled back with its transaction.
		assertThat(jdbc.queryForObject("select count(*) from refresh_token", Long.class)).isEqualTo(2);
		assertThat(jdbc.queryForObject(
				"select count(*) from refresh_token where revoked_at is not null", Long.class)).isEqualTo(1);
	}

	private static TokenPairResponse resultOf(Future<TokenPairResponse> outcome) {
		try {
			return outcome.get();
		} catch (Exception ex) {
			throw new IllegalStateException("Concurrent refresh attempt failed unexpectedly", ex);
		}
	}

	private UUID seedUser(String username, String role, boolean active) {
		return jdbc.queryForObject("""
				insert into app_user (username, password_hash, full_name, role, is_active,
					failed_login_attempts, locked_until, last_login_at, created_at, updated_at)
					values (?, ?, ?, ?, ?, 0, null, null, ?, ?)
					returning id""",
				UUID.class,
				username,
				passwordEncoder.encode(PASSWORD),
				"Refresh IT " + role,
				role,
				active,
				testNow,
				testNow);
	}

	private TokenPairResponse login() throws Exception {
		MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new LoginRequest(USERNAME, PASSWORD))))
				.andReturn();
		assertThat(result.getResponse().getStatus())
				.as("login body: %s", result.getResponse().getContentAsString())
				.isEqualTo(200);
		JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
		return new TokenPairResponse(data.get("access_token").asText(), data.get("refresh_token").asText());
	}

	private MvcResult refresh(String refreshToken) throws Exception {
		return mockMvc.perform(post("/api/v1/auth/refresh")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new RefreshTokenRequest(refreshToken))))
				.andReturn();
	}

	private void assertEnvelopeError(MvcResult result, String expectedCode) throws Exception {
		JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
		assertThat(body.get("data").isNull()).isTrue();
		assertThat(body.get("error").get("code").asText()).isEqualTo(expectedCode);
	}

	private static String sha256Hex(String input) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private void truncateDomainTables() {
		jdbc.execute("""
				TRUNCATE TABLE
					document_number_counter, refresh_token, idempotency_keys, job_run,
					reconciliation_exception, outbox_events,
					settlement_credit_application, settlement_allocation, penalty_adjustment, penalty_accrual,
					contract_credit_application, contract_credit, payment_allocation, payment,
					settlement_quote, settlement, installment, journal_line, journal_entry,
					contract, asset, customer
				CASCADE""");
	}

	/**
	 * Test-only synchronization for the rotation race: wrap the Spring Data repository so both
	 * refreshes can be released only after both have read the original token row. No production
	 * lock or synchronization is added; the conditional update remains the concurrency authority.
	 */
	@TestConfiguration(proxyBeanMethods = false)
	static class LookupBarrierConfiguration {

		@Bean
		RefreshTokenLookupBarrier refreshTokenLookupBarrier() {
			return new RefreshTokenLookupBarrier();
		}

		@Bean
		static BeanPostProcessor refreshTokenLookupBarrierPostProcessor(RefreshTokenLookupBarrier barrier) {
			return new BeanPostProcessor() {
				@Override
				public Object postProcessAfterInitialization(Object bean, String beanName) {
					if (!(bean instanceof RefreshTokenRepository repository)) {
						return bean;
					}
					if (Proxy.isProxyClass(bean.getClass())
							&& Proxy.getInvocationHandler(bean) instanceof LookupBarrierHandler) {
						return bean;
					}
					return Proxy.newProxyInstance(
							RefreshTokenRepository.class.getClassLoader(),
							new Class<?>[] { RefreshTokenRepository.class },
							new LookupBarrierHandler(repository, barrier));
				}
			};
		}
	}

	/** A one-shot barrier after {@code findByTokenHash}; disarmed for every other test. */
	static final class RefreshTokenLookupBarrier {

		private final AtomicInteger arrived = new AtomicInteger();
		private volatile CyclicBarrier barrier;

		void arm(int parties) {
			arrived.set(0);
			barrier = new CyclicBarrier(parties);
		}

		void disarm() {
			barrier = null;
		}

		int arrivedLookups() {
			return arrived.get();
		}

		void awaitAfterLookup() {
			CyclicBarrier current = barrier;
			if (current == null) {
				return;
			}
			arrived.incrementAndGet();
			try {
				current.await(10, TimeUnit.SECONDS);
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Interrupted while waiting for concurrent token lookups", ex);
			} catch (BrokenBarrierException | TimeoutException ex) {
				throw new IllegalStateException("Timed out waiting for concurrent token lookups", ex);
			}
		}
	}

	private record LookupBarrierHandler(
			RefreshTokenRepository delegate, RefreshTokenLookupBarrier barrier) implements InvocationHandler {

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
			try {
				Object result = method.invoke(delegate, args);
				if (method.getName().equals("findByTokenHash")) {
					barrier.awaitAfterLookup();
				}
				return result;
			} catch (InvocationTargetException ex) {
				throw ex.getCause();
			}
		}
	}
}
