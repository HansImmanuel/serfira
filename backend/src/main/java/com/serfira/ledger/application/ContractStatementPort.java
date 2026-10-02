package com.serfira.ledger.application;

import com.serfira.ledger.domain.LedgerRefType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The ledger's read surface for a contract statement (rekening koran, T9 / PRD C-5). The {@code ledger}
 * module owns what a posted movement is, so the statement reads straight from {@code journal_line}: it
 * reports what was posted and computes nothing (ADR-013 A-8).
 *
 * <p>This is a port in the <b>owning</b> module (02_TECH_SPEC.md §1): {@code contract} composes the
 * endpoint — it validates the contract exists and owns the HTTP response — and reads the lines through
 * here. The ledger never depends on {@code contract}, so this method takes a plain {@code contractId} and
 * does <b>no</b> existence check; an unknown id simply yields an empty page, and the caller is responsible
 * for turning that into a 404 when the contract does not exist.
 */
public interface ContractStatementPort {

	/**
	 * One page of the contract's journal lines, in chronological order (oldest {@code entry_date} first,
	 * then a stable tiebreak), filtered to the half-open business-time window and optionally to one event
	 * type.
	 *
	 * @param contractId   contract whose lines to read; never {@code null}
	 * @param fromInclusive window start, inclusive; {@code null} means no lower bound
	 * @param toExclusive   window end, exclusive; {@code null} means no upper bound
	 * @param refType       restrict to this event type, or {@code null} for every type
	 * @param pageable      page index and size (sort is fixed chronological, supplied by the caller)
	 * @return one page of lines, never {@code null}; empty when nothing matches or the contract has no lines
	 */
	Page<ContractStatementLine> statement(
			UUID contractId, OffsetDateTime fromInclusive, OffsetDateTime toExclusive,
			LedgerRefType refType, Pageable pageable);
}
