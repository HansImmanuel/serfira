package com.serfira.ledger.application;

import com.serfira.ledger.domain.LedgerRefType;
import com.serfira.ledger.infrastructure.JournalLineRepository;
import com.serfira.ledger.infrastructure.JournalLineRepository.StatementLineProjection;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Reads a contract's statement straight from the ledger (T9). Read-only: a statement reports what was
 * posted and never writes (ADR-013 A-8). The ledger owns this read; {@code contract} composes the endpoint
 * (existence check + HTTP response) and consumes {@link ContractStatementLine} snapshots through
 * {@link ContractStatementPort}, so no journal entity leaves the module (02_TECH_SPEC.md §1).
 */
@Service
@Transactional(readOnly = true)
public class ContractStatementService implements ContractStatementPort {

	private final JournalLineRepository lines;

	public ContractStatementService(JournalLineRepository lines) {
		this.lines = lines;
	}

	@Override
	public Page<ContractStatementLine> statement(
			UUID contractId, OffsetDateTime fromInclusive, OffsetDateTime toExclusive,
			LedgerRefType refType, Pageable pageable) {
		Objects.requireNonNull(contractId, "contractId");
		Objects.requireNonNull(pageable, "pageable");
		return lines.findContractStatement(contractId, fromInclusive, toExclusive, refType, pageable)
				.map(ContractStatementService::toLine);
	}

	/** Maps a projected row to the ledger-owned snapshot, resolving the account name from the enum. */
	private static ContractStatementLine toLine(StatementLineProjection projection) {
		return new ContractStatementLine(
				projection.getEntryDate(),
				projection.getAccount().code(),
				projection.getAccount().displayName(),
				projection.getDescription(),
				projection.getRefType(),
				projection.getRefId(),
				projection.getDebit(),
				projection.getCredit(),
				projection.getReversalOfId());
	}
}
