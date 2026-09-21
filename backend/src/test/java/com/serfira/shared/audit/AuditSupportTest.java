package com.serfira.shared.audit;

import com.serfira.shared.clock.FixedClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AuditSupportTest {

	private static final UUID SYSTEM = AuditContext.SYSTEM_USER_ID;
	private static final UUID ACTOR_A = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
	private static final UUID ACTOR_B = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

	private final FixedClock clock = new FixedClock(LocalDate.of(2026, 9, 12));
	private final AuditContext auditContext = new AuditContext();

	@AfterEach
	void tearDown() {
		AuditSupport.reset();
		// Prevent ThreadLocal actor leakage into other test classes running on this thread.
		auditContext.resetToSystem();
	}

	@Test
	void createFillsAllAuditFieldsFromClockAndActor() {
		AuditSupport.configure(clock, auditContext);

		TestAuditable entity = new TestAuditable();
		auditContext.runAs(ACTOR_A, () -> AuditSupport.onCreate(entity));

		assertThat(entity.getCreatedAt()).isEqualTo(clock.now());
		assertThat(entity.getCreatedBy()).isEqualTo(ACTOR_A);
		assertThat(entity.getUpdatedAt()).isEqualTo(clock.now());
		assertThat(entity.getUpdatedBy()).isEqualTo(ACTOR_A);
	}

	@Test
	void createDefaultsActorToSystemUser() {
		AuditSupport.configure(clock, auditContext);

		TestAuditable entity = new TestAuditable();
		AuditSupport.onCreate(entity);

		assertThat(entity.getCreatedBy()).isEqualTo(SYSTEM);
	}

	@Test
	void updateOnlyRefreshesUpdatedFields() {
		AuditSupport.configure(clock, auditContext);
		TestAuditable entity = new TestAuditable();
		auditContext.runAs(ACTOR_A, () -> AuditSupport.onCreate(entity));

		clock.advanceBy(Duration.ofDays(1));
		auditContext.runAs(ACTOR_B, () -> AuditSupport.onUpdate(entity));

		assertThat(entity.getCreatedAt()).isNotEqualTo(clock.now());
		assertThat(entity.getCreatedBy()).isEqualTo(ACTOR_A);
		assertThat(entity.getUpdatedAt()).isEqualTo(clock.now());
		assertThat(entity.getUpdatedBy()).isEqualTo(ACTOR_B);
	}

	@Test
	void nullAuditContextFallsBackToSystemActor() {
		AuditSupport.configure(clock, null);

		TestAuditable entity = new TestAuditable();
		AuditSupport.onCreate(entity);

		assertThat(entity.getCreatedBy()).isEqualTo(SYSTEM);
		assertThat(entity.getCreatedAt()).isEqualTo(clock.now());
	}

	@Test
	void auditContextRunAsRestoresPreviousActor() {
		auditContext.setActor(ACTOR_A);
		auditContext.runAs(ACTOR_B, () -> assertThat(auditContext.actorId()).isEqualTo(ACTOR_B));
		assertThat(auditContext.actorId()).isEqualTo(ACTOR_A);
	}

	@Test
	void fixedClockAcrossDayBoundaryKeepsZone() {
		OffsetDateTime at = OffsetDateTime.of(2026, 9, 12, 23, 59, 0, 0, ZoneOffset.ofHours(7));
		FixedClock c = new FixedClock(at);
		c.advanceBy(Duration.ofMinutes(2));
		assertThat(c.today()).isEqualTo(LocalDate.of(2026, 9, 13));
		assertThat(c.now().getOffset()).isEqualTo(ZoneOffset.ofHours(7));
	}

	@Test
	void immutableCreateFillsOnlyTheCreationFieldsFromClockAndActor() {
		AuditSupport.configure(clock, auditContext);

		TestImmutableAuditable entity = new TestImmutableAuditable();
		auditContext.runAs(ACTOR_A, () -> AuditSupport.onCreate(entity));

		assertThat(entity.getCreatedAt()).isEqualTo(clock.now());
		assertThat(entity.getCreatedBy()).isEqualTo(ACTOR_A);
	}

	@Test
	void immutableCreateDefaultsActorToSystemUser() {
		AuditSupport.configure(clock, auditContext);

		TestImmutableAuditable entity = new TestImmutableAuditable();
		AuditSupport.onCreate(entity);

		assertThat(entity.getCreatedBy()).isEqualTo(SYSTEM);
		assertThat(entity.getCreatedAt()).isEqualTo(clock.now());
	}

	@Test
	void immutableCreateKeepsAnExplicitlySetActor() {
		AuditSupport.configure(clock, auditContext);
		TestImmutableAuditable entity = new TestImmutableAuditable();
		entity.createdBy = ACTOR_B;

		auditContext.runAs(ACTOR_A, () -> AuditSupport.onCreate(entity));

		assertThat(entity.getCreatedBy()).isEqualTo(ACTOR_B);
	}

	/** Minimal entity for exercising the audit callbacks. */
	static class TestAuditable extends Auditable {
	}

	/** Minimal append-only entity: creation audit only, no update path (ADR-008). */
	static class TestImmutableAuditable extends ImmutableAuditable {
	}
}