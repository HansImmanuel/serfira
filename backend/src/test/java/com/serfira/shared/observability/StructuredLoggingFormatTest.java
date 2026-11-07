package com.serfira.shared.observability;

import com.serfira.shared.security.PiiMasker;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.Test;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T20 / Addendum §10 — the committed structured encoder ({@code logging.structured.format.console=ecs} in
 * {@code application.properties}) must emit the required JSON fields: {@code timestamp}, {@code level}, and
 * the MDC-carried {@code request_id} / {@code contract_id} / {@code module}. The test classpath deliberately
 * keeps plain-text console logging (see {@code src/test/resources/application.properties}), so this unit
 * test drives Spring Boot's public {@link StructuredLogEncoder} directly instead of capturing console
 * output: the encoder class is the very one the production {@code ecs} format resolves to.
 *
 * <p>PII: the encoder does not mask — masking is the caller's responsibility (steering {@code 60-security}:
 * log ids and safe metadata only, never PII). The second test pins the contract that a PII-bearing value
 * logged through {@link PiiMasker} leaks no raw NIK into the encoded line.
 */
class StructuredLoggingFormatTest {

	private static final String SAMPLE_NIK = "3171012501900001";
	private static final String MASKED_NIK = "3171********0001";
	private static final String CONTRACT_ID = "3f6b7d84-1111-4c1e-9c2d-3c71a2d9f001";

	private final LoggerContext loggerContext = new LoggerContext();
	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void ecsEncoderEmitsTheRequiredStructuredFields() {
		StructuredLogEncoder encoder = startedEcsEncoder();

		LoggingEvent event = event("payment posted");
		event.setInstant(Instant.parse("2026-11-07T01:00:00Z"));
		event.setMDCPropertyMap(Map.of(
				CorrelationId.REQUEST_ID, "req-123",
				CorrelationId.CONTRACT_ID, CONTRACT_ID,
				CorrelationId.MODULE, "payment"));

		JsonNode json = encodeToJson(encoder, event);

		assertThat(json.get("@timestamp").asText()).startsWith("2026-11-07T01:00:00");
		assertThat(json.get("log").get("level").asText()).isEqualTo("INFO");
		assertThat(json.get("message").asText()).isEqualTo("payment posted");
		// ECS lifts MDC entries to top-level fields — the Addendum §10 correlation fields.
		assertThat(json.get(CorrelationId.REQUEST_ID).asText()).isEqualTo("req-123");
		assertThat(json.get(CorrelationId.CONTRACT_ID).asText()).isEqualTo(CONTRACT_ID);
		assertThat(json.get(CorrelationId.MODULE).asText()).isEqualTo("payment");
		// The service name comes from the environment, as application.properties sets it for production.
		assertThat(json.get("service").get("name").asText()).isEqualTo("serfira-core");
	}

	@Test
	void aPiiBearingMessageLoggedThroughTheMaskerLeaksNoRawNik() {
		assertThat(PiiMasker.maskNik(SAMPLE_NIK)).isEqualTo(MASKED_NIK);
		StructuredLogEncoder encoder = startedEcsEncoder();

		// A compliant caller logs the masked value, never the raw NIK (steering 60-security).
		LoggingEvent event = event("customer " + PiiMasker.maskNik(SAMPLE_NIK) + " payment posted");
		event.setMDCPropertyMap(Map.of(CorrelationId.REQUEST_ID, "req-pii-1"));

		String encodedLine = new String(encoder.encode(event), StandardCharsets.UTF_8);
		assertThat(encodedLine).contains(MASKED_NIK);
		assertThat(encodedLine).doesNotContain(SAMPLE_NIK);
	}

	/**
	 * Starts the encoder exactly as Spring Boot's logback auto-configuration does: the format factory needs
	 * the Spring {@link Environment} stored on the logger context, and reads
	 * {@code logging.structured.ecs.service.name} from it (mirroring the committed production value).
	 */
	private StructuredLogEncoder startedEcsEncoder() {
		loggerContext.putObject(Environment.class.getName(),
				new MockEnvironment().withProperty("logging.structured.ecs.service.name", "serfira-core"));
		StructuredLogEncoder encoder = new StructuredLogEncoder();
		encoder.setContext(loggerContext);
		encoder.setFormat("ecs");
		encoder.setCharset(StandardCharsets.UTF_8);
		encoder.start();
		return encoder;
	}

	private LoggingEvent event(String message) {
		LoggingEvent event = new LoggingEvent();
		event.setLoggerContext(loggerContext);
		event.setLoggerName("com.serfira.payment.PaymentApplicationService");
		event.setLevel(Level.INFO);
		event.setMessage(message);
		event.setInstant(Instant.parse("2026-11-07T01:00:00Z"));
		return event;
	}

	private JsonNode encodeToJson(StructuredLogEncoder encoder, LoggingEvent event) {
		String encodedLine = new String(encoder.encode(event), StandardCharsets.UTF_8).trim();
		return objectMapper.readTree(encodedLine);
	}
}
