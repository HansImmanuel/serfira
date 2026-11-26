package com.serfira.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.serfira.TestcontainersConfiguration;
import com.serfira.shared.audit.AuditContext;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * T38 / ADR-027: the outbox writer only works inside a business transaction, a rollback drops the event, and a
 * committed event is a JSONB object carrying the request's audit actor.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class OutboxWriterIT {

	private static final UUID SYSTEM_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

	record ProbePayload(int schemaVersion, UUID contractId, OffsetDateTime closedAt) implements OutboxPayload {
	}

	@Autowired
	OutboxWriter writer;

	@Autowired
	TransactionTemplate transactions;

	@Autowired
	AuditContext auditContext;

	@Autowired
	JdbcTemplate jdbc;

	private UUID actorId;
	private final UUID contractId = UUID.randomUUID();
	private final ProbePayload payload = new ProbePayload(OutboxPayload.SCHEMA_VERSION, contractId,
			OffsetDateTime.parse("2026-11-26T10:15:30+07:00"));

	@BeforeEach
	void seedActor() {
		jdbc.execute("TRUNCATE TABLE outbox_events");
		actorId = jdbc.queryForObject("""
				insert into app_user (username, password_hash, full_name, role, is_active, created_at, updated_at)
					values (?, 'test-hash', 'Outbox IT Actor', 'ADMIN_OPERASIONAL', TRUE,
						clock_timestamp(), clock_timestamp())
					returning id""", UUID.class, "outbox-it-actor-" + UUID.randomUUID());
	}

	@AfterEach
	void leaveCleanSharedDatabase() {
		jdbc.execute("TRUNCATE TABLE outbox_events");
		jdbc.update("delete from app_user where id = ?", actorId);
	}

	@Test
	void appendOutsideATransactionIsRejected() {
		assertThatThrownBy(() -> writer.append(OutboxAggregateType.CONTRACT, contractId,
				OutboxEventType.ContractClosed, payload))
				.isInstanceOf(IllegalTransactionStateException.class);

		assertThat(rowCount()).isZero();
	}

	@Test
	void aRolledBackTransactionDropsItsEvent() {
		assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
			writer.append(OutboxAggregateType.CONTRACT, contractId, OutboxEventType.ContractClosed, payload);
			throw new IllegalStateException("business step failed after the append");
		})).isInstanceOf(IllegalStateException.class);

		assertThat(rowCount()).isZero();
	}

	@Test
	void aCommittedAppendStoresJsonbAndTheAuditActor() {
		UUID[] id = new UUID[1];
		auditContext.runAs(actorId, () -> transactions.executeWithoutResult(status ->
				id[0] = writer.append(OutboxAggregateType.CONTRACT, contractId, OutboxEventType.ContractClosed,
						payload)));

		assertThat(jdbc.queryForObject("""
				select status = 'PENDING' and jsonb_typeof(payload_json) = 'object' and created_by = ?
					and aggregate_type = 'CONTRACT' and event_type = 'ContractClosed' and retry_count = 0
					and sent_at is null
				from outbox_events where id = ?""", Boolean.class, actorId, id[0])).isTrue();
		assertThat(jdbc.queryForObject("select payload_json ->> 'contract_id' from outbox_events where id = ?",
				String.class, id[0])).isEqualTo(contractId.toString());
	}

	private int rowCount() {
		return jdbc.queryForObject("select count(*) from outbox_events", Integer.class);
	}
}
