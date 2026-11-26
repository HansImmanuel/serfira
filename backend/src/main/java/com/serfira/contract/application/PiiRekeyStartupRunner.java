package com.serfira.contract.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

/**
 * Runs the re-keying pass once at startup, synchronously (T28b Decision 5): the server is already accepting requests,
 * and a failure is logged and left in {@code job_run} but never stops the application.
 *
 * <p>Only the pass's own aborts are logged with their message, which carries counts or a customer id. Anything else is
 * logged by class name alone and without its stack trace, because a database message can quote a lookup hash
 * (Addendum §9).
 */
class PiiRekeyStartupRunner implements ApplicationRunner {

	private static final Logger LOGGER = LoggerFactory.getLogger(PiiRekeyStartupRunner.class);

	private final LockedPiiRekeyJob locked;
	private final PiiRekeyProperties properties;

	PiiRekeyStartupRunner(LockedPiiRekeyJob locked, PiiRekeyProperties properties) {
		this.locked = locked;
		this.properties = properties;
	}

	@Override
	public void run(ApplicationArguments args) {
		PiiRekeyMode mode = properties.mode();
		try {
			locked.run(mode);
		} catch (PiiRekeyPreflightException | PiiRekeyVerificationException abort) {
			LOGGER.error("pii-rekey {} run aborted ({}): {}", mode, abort.getClass().getSimpleName(),
					abort.getMessage());
		} catch (RuntimeException failure) {
			LOGGER.error("pii-rekey {} run aborted ({})", mode, failure.getClass().getName());
		}
	}
}
