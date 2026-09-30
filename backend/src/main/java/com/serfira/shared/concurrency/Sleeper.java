package com.serfira.shared.concurrency;

/**
 * Injectable indirection over a blocking wait, so a bounded retry loop (e.g. `docs/tasks.md` T5) can be
 * unit tested without actually pausing the thread. Production code uses {@link #systemSleeper()}; tests
 * inject a fake that records the requested durations.
 */
public interface Sleeper {

	/** Blocks for {@code millis} milliseconds. Restores the interrupt flag and returns early if interrupted. */
	void sleep(long millis);

	static Sleeper systemSleeper() {
		return millis -> {
			try {
				Thread.sleep(millis);
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
			}
		};
	}
}
