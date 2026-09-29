package com.serfira.shared.job.domain;

/** Persistent lifecycle of one auditable background-job step invocation. */
public enum JobRunStatus {
	RUNNING,
	COMPLETED,
	FAILED
}
