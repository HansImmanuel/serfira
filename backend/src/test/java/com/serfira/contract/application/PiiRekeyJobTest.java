package com.serfira.contract.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.serfira.contract.infrastructure.CustomerPiiRows;
import com.serfira.shared.audit.AuditContext;
import com.serfira.shared.clock.FixedClock;
import com.serfira.shared.idempotency.IdempotencyKeyUsage;
import com.serfira.shared.idempotency.IdempotencyKeyUsage.FingerprintKeyUsage;
import com.serfira.shared.job.application.JobRunService;
import com.serfira.shared.security.PiiSecuritySupport;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * T28b Decision 4: {@code EXECUTE} refuses to run while new values are still written as {@code v1}, before any read
 * or write, leaving a {@code FAILED/0/0} run; {@code DRY_RUN} runs in either envelope mode.
 */
class PiiRekeyJobTest {

	private static final byte[] K1_AES = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
	private static final byte[] K1_HMAC = "abcdef0123456789abcdef0123456789".getBytes(StandardCharsets.UTF_8);
	private static final LocalDate TODAY = LocalDate.of(2026, 12, 1);

	private final CustomerPiiRows rows = mock(CustomerPiiRows.class);
	private final CustomerRekeyPageWriter pageWriter = mock(CustomerRekeyPageWriter.class);
	private final IdempotencyKeyUsage fingerprints = mock(IdempotencyKeyUsage.class);
	private final JobRunService jobRuns = mock(JobRunService.class);
	private final UUID runId = UUID.randomUUID();
	private final PiiRekeyJob job = new PiiRekeyJob(rows, pageWriter, fingerprints, jobRuns, new AuditContext(),
			new FixedClock(TODAY), 500);

	@BeforeEach
	void bindTheTestProfileRingWhichWritesV1() {
		// The test-profile ring: k1 only, v1 writes (ADR-025 D7 phase 1).
		PiiSecuritySupport.configure(K1_AES, K1_HMAC);
		when(jobRuns.start(PiiRekeyJob.JOB_NAME, TODAY)).thenReturn(runId);
	}

	@AfterEach
	void restoreTestProfileRing() {
		PiiSecuritySupport.configure(K1_AES, K1_HMAC);
	}

	@Test
	void executeRefusesV1WritesBeforeReadingAnything() {
		assertThatThrownBy(() -> job.run(PiiRekeyMode.EXECUTE))
				.isInstanceOf(PiiRekeyPreflightException.class)
				.hasMessage("pii-rekey EXECUTE needs serfira.security.pii.write-envelope=v2 (ADR-025 D7)");

		verify(jobRuns).failHard(runId, 0, 0);
		verify(jobRuns, never()).complete(any(), anyInt(), anyInt());
		verifyNoInteractions(rows, pageWriter);
	}

	@Test
	void aDryRunIsAllowedWhileWritingV1() {
		when(rows.pageAfter(new UUID(0L, 0L), 500)).thenReturn(List.of());
		when(fingerprints.unexpired()).thenReturn(new FingerprintKeyUsage(Map.of(), 0L));

		PiiRekeyOutcome outcome = job.run(PiiRekeyMode.DRY_RUN);

		assertThat(outcome.rekeyedRows()).isZero();
		verify(jobRuns).complete(runId, 0, 0);
		verifyNoInteractions(pageWriter);
	}
}
