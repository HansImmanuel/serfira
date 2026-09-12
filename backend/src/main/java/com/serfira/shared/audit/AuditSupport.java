package com.serfira.shared.audit;

import com.serfira.shared.clock.Clock;
import com.serfira.shared.clock.SystemClock;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Static bridge between JPA lifecycle callbacks and the Spring beans ({@code Clock}, {@code AuditContext}).
 *
 * <p>Hibernate instantiates JPA listeners itself, so Spring beans cannot be injected into {@link Auditable}
 * callbacks directly. Instead the beans are bound once at startup by
 * {@link AuditConfiguration#auditContext(Clock)}. The bridge stays internal to the audit package; the default
 * clock is a {@link SystemClock} so entities remain writable even before the context is configured.
 */
public final class AuditSupport {

	private static final Clock FALLBACK_CLOCK = new SystemClock();

	private static volatile Clock clock = FALLBACK_CLOCK;
	private static volatile AuditContext auditContext;

	private AuditSupport() {
		// static holder only
	}

	/** Binds the application clock/audit context for all audit callbacks. Idempotent. */
	public static synchronized void configure(Clock clock, AuditContext auditContext) {
		AuditSupport.clock = clock == null ? FALLBACK_CLOCK : clock;
		AuditSupport.auditContext = auditContext;
	}

	/** Restores defaults (used between tests). */
	public static synchronized void reset() {
		AuditSupport.clock = FALLBACK_CLOCK;
		AuditSupport.auditContext = null;
	}

	static void onCreate(Auditable entity) {
		OffsetDateTime now = clock.now();
		UUID actor = currentActor();
		if (entity.createdAt == null) {
			entity.createdAt = now;
		}
		if (entity.createdBy == null) {
			entity.createdBy = actor;
		}
		entity.updatedAt = now;
		if (entity.updatedBy == null) {
			entity.updatedBy = actor;
		}
	}

	static void onUpdate(Auditable entity) {
		entity.updatedAt = clock.now();
		entity.updatedBy = currentActor();
	}

	private static UUID currentActor() {
		return auditContext == null ? AuditContext.SYSTEM_USER_ID : auditContext.actorId();
	}
}