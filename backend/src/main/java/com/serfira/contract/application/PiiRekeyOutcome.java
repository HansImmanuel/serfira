package com.serfira.contract.application;

/**
 * What a finished re-keying run did: the rows it re-keyed, the census after the run and the retirement report built
 * from it. Counts and key ids only.
 */
public record PiiRekeyOutcome(PiiRekeyMode mode, int rekeyedRows, CustomerKeyCensus census,
		PiiRetirementReport report) {
}
