package com.serfira.shared.audit;

import java.util.Objects;
import java.util.UUID;

/**
 * Holder for the actor (application user) of the current unit of work.
 *
 * <p>Backed by a {@link ThreadLocal} so that parallel jobs / tests never leak actors. The default actor is
 * the seeded non-interactive {@code SYSTEM} user (Addendum §3.3). {@code AuditActorBindingFilter} binds the
 * JWT principal of authenticated requests via {@link #runAs(UUID, Runnable)}; background jobs run under SYSTEM.
 */
public final class AuditContext {

	/** Id of the seeded non-interactive SYSTEM user (see {@code V1__baseline.sql}). */
	public static final UUID SYSTEM_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

	private static final ThreadLocal<UUID> ACTOR = ThreadLocal.withInitial(() -> SYSTEM_USER_ID);

	/** Current actor id, or the SYSTEM user when no actor is bound. */
	public UUID actorId() {
		return ACTOR.get();
	}

	/** Runs {@code action} under the given actor id and restores the previous actor afterwards. */
	public void runAs(UUID actorId, Runnable action) {
		Objects.requireNonNull(actorId, "actorId");
		Objects.requireNonNull(action, "action");
		UUID previous = ACTOR.get();
		ACTOR.set(actorId);
		try {
			action.run();
		} finally {
			ACTOR.set(previous);
		}
	}

	/** Binds the actor for the remainder of the current thread of execution. */
	public void setActor(UUID actorId) {
		ACTOR.set(Objects.requireNonNull(actorId, "actorId"));
	}

	/** Restores the default SYSTEM actor for the current thread. */
	public void resetToSystem() {
		ACTOR.set(SYSTEM_USER_ID);
	}
}