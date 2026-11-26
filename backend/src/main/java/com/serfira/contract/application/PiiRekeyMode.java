package com.serfira.contract.application;

/** What a re-keying run does (T28b): {@code DRY_RUN} only counts and reports; {@code EXECUTE} also re-keys. */
public enum PiiRekeyMode {
	DRY_RUN, EXECUTE
}
