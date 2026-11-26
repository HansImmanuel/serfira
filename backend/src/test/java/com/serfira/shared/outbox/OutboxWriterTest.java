package com.serfira.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.serfira.shared.clock.FixedClock;
import com.serfira.shared.money.BigDecimalStringModule;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/** T38: the writer persists one PENDING row whose payload is snake_case JSON with money as a decimal string. */
class OutboxWriterTest {

	record SamplePayload(int schemaVersion, UUID paymentId, BigDecimal amount, OffsetDateTime paidAt)
			implements OutboxPayload {
	}

	private final EntityManager entityManager = mock(EntityManager.class);
	private final JsonMapper mapper = JsonMapper.builder()
			.propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
			.addModule(new BigDecimalStringModule())
			.build();
	private final OutboxWriter writer =
			new OutboxWriter(entityManager, mapper, new OutboxEventIds(new FixedClock(LocalDate.of(2026, 11, 26))));

	private final UUID paymentId = UUID.randomUUID();
	private final SamplePayload payload = new SamplePayload(OutboxPayload.SCHEMA_VERSION, paymentId,
			new BigDecimal("4769959.90"), OffsetDateTime.parse("2026-11-26T10:15:30+07:00"));

	@Test
	void appendPersistsOnePendingRowWithTheGivenTypeAndAggregate() {
		UUID id = writer.append(OutboxAggregateType.PAYMENT, paymentId, OutboxEventType.PaymentReceived, payload);

		OutboxEvent event = persisted();
		assertThat(event.getId()).isEqualTo(id);
		assertThat(event.getAggregateType()).isEqualTo(OutboxAggregateType.PAYMENT);
		assertThat(event.getAggregateId()).isEqualTo(paymentId);
		assertThat(event.getEventType()).isEqualTo(OutboxEventType.PaymentReceived);
		assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
		assertThat(event.getRetryCount()).isZero();
		assertThat(event.getSentAt()).isNull();
	}

	@Test
	void moneyIsADecimalStringAndKeysAreSnakeCase() {
		writer.append(OutboxAggregateType.PAYMENT, paymentId, OutboxEventType.PaymentReceived, payload);

		String json = persisted().getPayloadJson();
		assertThat(json).contains("\"amount\":\"4769959.90\"");
		assertThat(json).contains("\"payment_id\":\"" + paymentId + "\"");
		assertThat(json).contains("\"schema_version\":1");
		assertThat(json).doesNotContain("paymentId");
	}

	@Test
	void aNullPayloadOrTypeIsRejected() {
		assertThatThrownBy(() -> writer.append(OutboxAggregateType.PAYMENT, paymentId,
				OutboxEventType.PaymentReceived, null)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> writer.append(OutboxAggregateType.PAYMENT, paymentId, null, payload))
				.isInstanceOf(NullPointerException.class);

		verify(entityManager, never()).persist(any());
	}

	private OutboxEvent persisted() {
		ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
		verify(entityManager).persist(captor.capture());
		return captor.getValue();
	}
}
