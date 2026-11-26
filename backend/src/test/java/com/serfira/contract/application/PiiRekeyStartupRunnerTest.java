package com.serfira.contract.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * T28b Decision 5: the startup runner logs a failed pass and lets the application start. Only its own abort
 * exceptions are logged with their message (counts or a customer id); anything else is logged by class name, because
 * a database message can quote a lookup hash.
 */
class PiiRekeyStartupRunnerTest {

	private static final String PREFLIGHT_MESSAGE = "pii-rekey pre-flight aborted with zero writes: 0 unknown-kid, "
			+ "0 undecryptable, 1 duplicate NIK, 0 duplicate phone rows";
	private static final String FULL_HASH = "a".repeat(64);

	private final LockedPiiRekeyJob locked = mock(LockedPiiRekeyJob.class);
	private final PiiRekeyStartupRunner runner = new PiiRekeyStartupRunner(locked,
			new PiiRekeyProperties(PiiRekeyMode.EXECUTE, 500));
	private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
	private Logger logger;

	@BeforeEach
	void attachAppender() {
		logger = (Logger) LoggerFactory.getLogger(PiiRekeyStartupRunner.class);
		appender.start();
		logger.addAppender(appender);
	}

	@AfterEach
	void detachAppender() {
		logger.detachAppender(appender);
		appender.stop();
	}

	@Test
	void anAbortIsLoggedWithItsMessage() {
		doThrow(new PiiRekeyPreflightException(PREFLIGHT_MESSAGE)).when(locked).run(PiiRekeyMode.EXECUTE);

		runner.run(new DefaultApplicationArguments());

		assertThat(errorLines()).singleElement().asString().contains("1 duplicate NIK").contains("EXECUTE");
	}

	@Test
	void aVerificationFailureIsLoggedWithItsMessage() {
		UUID customer = UUID.randomUUID();
		doThrow(new PiiRekeyVerificationException(customer, "phone_lookup is not the active-key hash"))
				.when(locked).run(PiiRekeyMode.EXECUTE);

		runner.run(new DefaultApplicationArguments());

		assertThat(errorLines()).singleElement().asString().contains(customer.toString()).contains("phone_lookup");
	}

	@Test
	void aDatabaseErrorIsLoggedByClassNameOnly() {
		doThrow(new DataIntegrityViolationException("Key (nik_hash)=(" + FULL_HASH + ") already exists"))
				.when(locked).run(PiiRekeyMode.EXECUTE);

		runner.run(new DefaultApplicationArguments());

		assertThat(errorLines()).singleElement().asString()
				.contains("DataIntegrityViolationException")
				.doesNotContain(FULL_HASH);
		// No throwable is attached either: a logged stack trace would print the message.
		assertThat(appender.list).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
	}

	@Test
	void aFailureNeverEscapes() {
		doThrow(new PiiRekeyPreflightException(PREFLIGHT_MESSAGE)).when(locked).run(PiiRekeyMode.EXECUTE);
		assertThatCode(() -> runner.run(new DefaultApplicationArguments())).doesNotThrowAnyException();

		doThrow(new DataIntegrityViolationException("Key (nik_hash)=(" + FULL_HASH + ") already exists"))
				.when(locked).run(PiiRekeyMode.EXECUTE);
		assertThatCode(() -> runner.run(new DefaultApplicationArguments())).doesNotThrowAnyException();
	}

	private List<String> errorLines() {
		return appender.list.stream()
				.filter(event -> event.getLevel() == Level.ERROR)
				.map(ILoggingEvent::getFormattedMessage)
				.toList();
	}
}
