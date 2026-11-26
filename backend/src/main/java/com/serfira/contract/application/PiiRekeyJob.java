package com.serfira.contract.application;

import com.serfira.contract.application.CustomerRekeyPageWriter.PageResult;
import com.serfira.contract.infrastructure.CustomerPiiRow;
import com.serfira.contract.infrastructure.CustomerPiiRows;
import com.serfira.shared.audit.AuditContext;
import com.serfira.shared.clock.Clock;
import com.serfira.shared.idempotency.IdempotencyKeyUsage;
import com.serfira.shared.job.application.JobRunService;
import com.serfira.shared.security.PiiEnvelopeVersion;
import com.serfira.shared.security.PiiSecuritySupport;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The PII re-keying pass (T28b, ADR-025): moves every customer's envelopes and lookup hashes to the active key and
 * reports, per ring key, whether it can be retired.
 *
 * <p>Runs as the SYSTEM actor with one {@code job_run} row named {@code pii-rekey}, abandoning a crash remnant first
 * like {@code IdempotencyCleanupJob}. A census decrypts every row before anything is written: {@code EXECUTE} refuses
 * to start while new values are still written as {@code v1} (ADR-025 D7, D9) or when a row is unreadable or stored twice,
 * leaving zero writes. It then re-keys page by page through {@link CustomerRekeyPageWriter}, each page verified and
 * committed on its own, so a rerun after an abort resumes where the committed pages end. The cursor advances by the
 * rows scanned, not the rows rewritten, so pages already on target are passed over.
 *
 * <p>The {@code job_run} counters follow ADR-025 D9. A failure is recorded and rethrown; this class logs no exception
 * message, so a database error quoting a lookup hash never reaches the log from here.
 */
public class PiiRekeyJob {

	static final String JOB_NAME = "pii-rekey";
	private static final UUID FIRST_ID = new UUID(0L, 0L);
	private static final Logger LOGGER = LoggerFactory.getLogger(PiiRekeyJob.class);

	private final CustomerPiiRows rows;
	private final CustomerRekeyPageWriter pageWriter;
	private final IdempotencyKeyUsage fingerprints;
	private final JobRunService jobRuns;
	private final AuditContext auditContext;
	private final Clock clock;
	private final int batchSize;

	PiiRekeyJob(CustomerPiiRows rows, CustomerRekeyPageWriter pageWriter, IdempotencyKeyUsage fingerprints,
			JobRunService jobRuns, AuditContext auditContext, Clock clock, int batchSize) {
		if (batchSize < 1) {
			throw new IllegalArgumentException("batch-size must be positive");
		}
		this.rows = rows;
		this.pageWriter = pageWriter;
		this.fingerprints = fingerprints;
		this.jobRuns = jobRuns;
		this.auditContext = auditContext;
		this.clock = clock;
		this.batchSize = batchSize;
	}

	/**
	 * Runs one pass under the SYSTEM actor.
	 *
	 * @throws PiiRekeyPreflightException when {@code EXECUTE} refuses to write anything
	 * @throws PiiRekeyVerificationException when a re-keyed row does not read back as written; its page rolls back
	 */
	public PiiRekeyOutcome run(PiiRekeyMode mode) {
		AtomicReference<PiiRekeyOutcome> outcome = new AtomicReference<>();
		auditContext.runAs(AuditContext.SYSTEM_USER_ID, () -> outcome.set(runAsSystem(mode)));
		return outcome.get();
	}

	private PiiRekeyOutcome runAsSystem(PiiRekeyMode mode) {
		jobRuns.abandonStaleRuns(List.of(JOB_NAME));
		UUID runId = jobRuns.start(JOB_NAME, clock.today());
		int rekeyed = 0;
		// ADR-025 D9: a pre-flight abort records its blocking rows; any later abort records 1.
		int failed = 1;
		try {
			boolean execute = mode == PiiRekeyMode.EXECUTE;
			if (execute && PiiSecuritySupport.writeEnvelope() == PiiEnvelopeVersion.V1) {
				failed = 0;
				throw new PiiRekeyPreflightException(
						"pii-rekey EXECUTE needs serfira.security.pii.write-envelope=v2 (ADR-025 D7)");
			}
			CustomerKeyCensus census = census();
			// Every mode logs the census, so a DRY_RUN says in advance whether EXECUTE would refuse or abort.
			LOGGER.info(census.censusLine(executeWouldRun(census)));
			if (execute && census.blocksRekey()) {
				logReport(census);
				failed = Math.toIntExact(census.unknownKidRows() + census.undecryptableRows()
						+ census.duplicateNikRows() + census.duplicatePhoneRows());
				throw new PiiRekeyPreflightException("pii-rekey pre-flight aborted with zero writes: "
						+ census.unknownKidRows() + " unknown-kid, " + census.undecryptableRows() + " undecryptable, "
						+ census.duplicateNikRows() + " duplicate NIK, " + census.duplicatePhoneRows()
						+ " duplicate phone rows");
			}
			if (execute) {
				UUID after = FIRST_ID;
				PageResult page;
				do {
					page = pageWriter.rekeyPage(after, batchSize);
					rekeyed += page.rekeyed();
					if (page.lastScannedId() != null) {
						after = page.lastScannedId();
					}
				} while (page.scanned() == batchSize);
				census = census();
			}
			PiiRetirementReport report = logReport(census);
			jobRuns.complete(runId, rekeyed, 0);
			LOGGER.info("pii-rekey {} run completed: {} rows re-keyed", mode, rekeyed);
			return new PiiRekeyOutcome(mode, rekeyed, census, report);
		} catch (RuntimeException failure) {
			// Mirrors IdempotencyCleanupJob: finalize FAILED with the rows committed so far, keep a finalization
			// failure as suppressed so it never masks the cause, and rethrow.
			try {
				jobRuns.failHard(runId, rekeyed, failed);
			} catch (RuntimeException finalizationFailure) {
				failure.addSuppressed(finalizationFailure);
			}
			throw failure;
		}
	}

	/** Read-only tally of every customer row, paged by id like the re-key itself. */
	private CustomerKeyCensus census() {
		CustomerKeyCensus.Builder census = CustomerKeyCensus.builder();
		UUID after = FIRST_ID;
		List<CustomerPiiRow> page;
		do {
			page = rows.pageAfter(after, batchSize);
			page.forEach(census::accept);
			if (!page.isEmpty()) {
				after = page.get(page.size() - 1).id();
			}
		} while (page.size() == batchSize);
		return census.build();
	}

	private static boolean executeWouldRun(CustomerKeyCensus census) {
		return !census.blocksRekey() && PiiSecuritySupport.writeEnvelope() != PiiEnvelopeVersion.V1;
	}

	private PiiRetirementReport logReport(CustomerKeyCensus census) {
		PiiRetirementReport report = PiiRetirementReport.of(census, fingerprints.unexpired(),
				PiiSecuritySupport.ringKids(), PiiSecuritySupport.activeKid(), PiiSecuritySupport.legacyKid());
		report.logLines().forEach(LOGGER::info);
		return report;
	}
}
