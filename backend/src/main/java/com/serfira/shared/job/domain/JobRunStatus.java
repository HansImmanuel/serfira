package com.serfira.shared.job.domain;

/** Persistent lifecycle of one auditable background-job step invocation. */
public enum JobRunStatus {
	RUNNING,
	COMPLETED,
	FAILED,
	/**
	 * Terminal crash-recovery status (CR-07, ADR-013 A-9 implementation note T26). Set only when a later
	 * locked run observes a leftover {@code RUNNING} row whose process died, never produced by a normal
	 * {@code complete(...)}. Distinct from {@code FAILED} ("ran to completion with failed contracts") so the
	 * audit trail does not conflate "the JVM died mid-run" with "the run finished and some contracts failed".
	 */
	ABANDONED
}
