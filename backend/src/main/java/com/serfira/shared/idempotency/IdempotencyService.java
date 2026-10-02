package com.serfira.shared.idempotency;

import com.serfira.shared.audit.AuditContext;
import com.serfira.shared.clock.Clock;
import com.serfira.shared.config.SystemParameterKeys;
import com.serfira.shared.config.SystemParameterService;
import com.serfira.shared.error.BadRequestException;
import com.serfira.shared.error.ConflictException;
import com.serfira.shared.error.IdempotencyKeyExpiredException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Retry safety for mutations that can be replayed (02_TECH_SPEC.md §2.5).
 *
 * <p>Contract with the caller:
 * <ol>
 *   <li>call inside the caller's transaction ({@link Propagation#MANDATORY}) — the claim and the
 *       business write must commit or roll back together,</li>
 *   <li>pass the endpoint scope, the client {@code Idempotency-Key}, the canonical request JSON
 *       ({@link CanonicalRequestJson}) and a supplier that executes the mutation.</li>
 * </ol>
 *
 * <p>Behaviour: identical request under the same key → the stored response is replayed and the
 * supplier is <b>never</b> invoked again; a different request under the same key → 409
 * {@code CONFLICT}; no key → 400 {@code VALIDATION_ERROR}. Only the keyed request fingerprint and
 * the response payload are stored — never the raw request, so no plaintext PII is persisted.
 *
 * <p>Retention (TS §2.5, ADR-017, amends ADR-007 decision 9): the claim carries {@code expires_at}
 * = {@code now + IDEMPOTENCY_KEY_RETENTION_DAYS}. A key is <b>single-use forever</b> per endpoint;
 * the window only bounds how long the stored response is replayed. Inside the window a retry
 * replays the stored response (or conflicts on a different body); once it has elapsed, reusing the
 * key is rejected with 409 {@code IDEMPOTENCY_KEY_EXPIRED} <b>before the supplier runs</b>, so no
 * business work is redone — the key is never taken over and the mutation is never executed twice.
 * The permanent business-row backstops ({@code uq_contract_idempotency},
 * {@code uq_payment_idempotency}) return the same code once the F4 cleanup job (story T18) has
 * deleted the row. The caller must mint a fresh key for a genuinely new request.
 */
@Service
public class IdempotencyService {

	private static final Logger LOGGER = LoggerFactory.getLogger(IdempotencyService.class);

	private static final int MAX_KEY_LENGTH = 80;

	private final IdempotencyKeyRepository keys;
	private final SystemParameterService systemParameters;
	private final Clock clock;
	private final AuditContext auditContext;
	private final ObjectMapper objectMapper;

	public IdempotencyService(IdempotencyKeyRepository keys, SystemParameterService systemParameters, Clock clock,
			AuditContext auditContext, ObjectMapper objectMapper) {
		this.keys = keys;
		this.systemParameters = systemParameters;
		this.clock = clock;
		this.auditContext = auditContext;
		this.objectMapper = objectMapper;
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public <T> IdempotentResult<T> execute(String endpoint, String rawKey, String canonicalRequestJson,
			Class<T> responseType, Supplier<T> operation) {
		Objects.requireNonNull(operation, "operation");
		String key = requireKey(rawKey);
		String fingerprint = RequestFingerprint.of(canonicalRequestJson);

		OffsetDateTime now = clock.now();
		int retentionDays = systemParameters.requireInt(SystemParameterKeys.IDEMPOTENCY_KEY_RETENTION_DAYS);
		OffsetDateTime expiresAt = now.plusDays(retentionDays);
		UUID actor = auditContext.actorId();
		int claimed = keys.claim(UUID.randomUUID(), key, endpoint, fingerprint, IdempotencyKey.STATUS_IN_PROGRESS,
				expiresAt, now, actor);

		if (claimed == 0) {
			// The key already names a claim. Inside the retention window it is a retry (replay or
			// conflict, never a second execution); past the window it is rejected before the supplier
			// runs, because a key is single-use forever and is never re-run (ADR-017, option A).
			return replayOrRejectExpired(endpoint, key, fingerprint, now, responseType);
		}

		T response = operation.get();
		IdempotencyKey row = keys.findByEndpointAndKey(endpoint, key)
				.orElseThrow(() -> new IllegalStateException(
						"idempotency row disappeared for endpoint '" + endpoint + "'"));
		row.complete(serialize(response));
		keys.saveAndFlush(row);
		return IdempotentResult.executed(response);
	}

	private <T> IdempotentResult<T> replayOrRejectExpired(String endpoint, String key, String fingerprint,
			OffsetDateTime now, Class<T> responseType) {
		IdempotencyKey existing = keys.findByEndpointAndKey(endpoint, key)
				.orElseThrow(() -> new IllegalStateException(
						"idempotency row disappeared for endpoint '" + endpoint + "'"));
		OffsetDateTime expiresAt = existing.getExpiresAt();
		if (expiresAt != null && !expiresAt.isAfter(now)) {
			// Option A (ADR-017): past retention the key is spent, whatever the body. Reject before the
			// supplier runs so no billing, accrual, contract or payment work is redone.
			LOGGER.info("Idempotency-Key reused past retention on {}; rejected as expired", endpoint);
			throw new IdempotencyKeyExpiredException(
					"Idempotency-Key has expired on " + endpoint + "; use a fresh key for a new request");
		}
		if (!Objects.equals(existing.getRequestHash(), fingerprint)) {
			// TS §2.5: same key with a different request is a client conflict, never a second execution.
			throw new ConflictException("Idempotency-Key was already used for a different request on " + endpoint);
		}
		if (existing.getResponseJson() == null) {
			throw new ConflictException("Idempotency-Key is already in progress for " + endpoint + "; retry shortly");
		}
		LOGGER.info("Replayed stored response for {} (fingerprint {})", endpoint, fingerprint.substring(0, 12));
		return IdempotentResult.replayed(deserialize(existing.getResponseJson(), responseType));
	}

	private static String requireKey(String rawKey) {
		if (rawKey == null || rawKey.isBlank()) {
			throw new BadRequestException("Idempotency-Key header is required for this endpoint");
		}
		String key = rawKey.trim();
		if (key.length() > MAX_KEY_LENGTH) {
			throw new BadRequestException("Idempotency-Key must be at most " + MAX_KEY_LENGTH + " characters");
		}
		return key;
	}

	private String serialize(Object response) {
		try {
			return objectMapper.writeValueAsString(response);
		} catch (RuntimeException ex) {
			throw new IllegalStateException("failed to store the idempotent response payload", ex);
		}
	}

	private <T> T deserialize(String json, Class<T> responseType) {
		try {
			return objectMapper.readValue(json, responseType);
		} catch (RuntimeException ex) {
			throw new IllegalStateException("stored idempotent response payload could not be read", ex);
		}
	}
}