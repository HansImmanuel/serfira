package com.serfira.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * T38 / ADR-027: the log sink writes one INFO line per event, and under the production ECS format the event id,
 * type, aggregate and payload are JSON fields of that line (the encoder is the one {@code StructuredLoggingFormatTest}
 * drives).
 */
class LoggingOutboxSinkTest {

	private final Logger logger = (Logger) LoggerFactory.getLogger(LoggingOutboxSink.class);
	private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
	private Level previousLevel;

	private final UUID aggregateId = UUID.randomUUID();
	private final OutboxEvent event = new OutboxEvent(UUID.randomUUID(), OutboxAggregateType.CONTRACT, aggregateId,
			OutboxEventType.ContractClosed, "{\"schema_version\":1,\"closed_reason\":\"MATURITY\"}");

	@BeforeEach
	void attach() {
		appender.start();
		logger.addAppender(appender);
		previousLevel = logger.getLevel();
		logger.setLevel(Level.INFO);
	}

	@AfterEach
	void detach() {
		logger.detachAppender(appender);
		logger.setLevel(previousLevel);
	}

	@Test
	void eachEventIsOneInfoLineWithItsIdentityAndPayload() {
		new LoggingOutboxSink().publish(event);

		assertThat(appender.list).hasSize(1);
		ILoggingEvent line = appender.list.get(0);
		assertThat(line.getLevel()).isEqualTo(Level.INFO);
		assertThat(line.getFormattedMessage()).isEqualTo("Published outbox event");
		assertThat(line.getKeyValuePairs()).extracting(pair -> pair.key + "=" + pair.value).containsExactly(
				"event_id=" + event.getId(),
				"event_type=ContractClosed",
				"aggregate_type=CONTRACT",
				"aggregate_id=" + aggregateId,
				"payload=" + event.getPayloadJson());
	}

	@Test
	void theEcsLineCarriesTheEventFields() {
		new LoggingOutboxSink().publish(event);

		JsonNode json = new ObjectMapper().readTree(new String(ecsEncoder().encode(appender.list.get(0)),
				StandardCharsets.UTF_8));
		assertThat(json.get("message").asText()).isEqualTo("Published outbox event");
		assertThat(json.get("event_id").asText()).isEqualTo(event.getId().toString());
		assertThat(json.get("event_type").asText()).isEqualTo("ContractClosed");
		assertThat(json.get("aggregate_id").asText()).isEqualTo(aggregateId.toString());
		assertThat(json.get("payload").asText()).isEqualTo(event.getPayloadJson());
	}

	private static StructuredLogEncoder ecsEncoder() {
		LoggerContext context = new LoggerContext();
		context.putObject(Environment.class.getName(),
				new MockEnvironment().withProperty("logging.structured.ecs.service.name", "serfira-core"));
		StructuredLogEncoder encoder = new StructuredLogEncoder();
		encoder.setContext(context);
		encoder.setFormat("ecs");
		encoder.setCharset(StandardCharsets.UTF_8);
		encoder.start();
		return encoder;
	}
}
