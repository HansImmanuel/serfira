package com.serfira.ledger.domain;

import com.serfira.shared.audit.ImmutableAuditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One debit or credit of a {@link JournalEntry} (03_DOMAIN_MODEL.md §1.13, ADR-002).
 *
 * <p><b>Immutable:</b> V1's {@code trg_journal_line_immutable} trigger rejects UPDATE/DELETE.
 * {@code entryDate} is denormalized from the entry so the documented {@code (account_code, entry_date)}
 * index can serve account/period lookups; because lines are never updated it cannot drift.
 * {@code accountCode} is the chart-of-accounts code the entry's line refers to (FK to {@code accounts}).
 */
@Entity
@Table(name = "journal_line")
public class JournalLine extends ImmutableAuditable {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "journal_entry_id", nullable = false)
	private JournalEntry journalEntry;

	@Column(name = "entry_date", nullable = false, columnDefinition = "timestamptz")
	private OffsetDateTime entryDate;

	@Enumerated(EnumType.STRING)
	@Column(name = "account_code", nullable = false, length = 30)
	private LedgerAccount account;

	@Column(name = "debit", nullable = false, precision = 19, scale = 2)
	private BigDecimal debit;

	@Column(name = "credit", nullable = false, precision = 19, scale = 2)
	private BigDecimal credit;

	@Column(name = "contract_id")
	private UUID contractId;

	protected JournalLine() {
		// JPA
	}

	/**
	 * Creates a line of {@code entry}. Package-private: lines only exist as part of an entry, built from
	 * a validated {@link LedgerPostingLine}.
	 */
	JournalLine(JournalEntry journalEntry, OffsetDateTime entryDate, LedgerPostingLine line) {
		this.journalEntry = journalEntry;
		this.entryDate = entryDate;
		this.account = line.account();
		this.debit = line.debit();
		this.credit = line.credit();
		this.contractId = line.contractId();
	}

	public UUID getId() {
		return id;
	}

	public JournalEntry getJournalEntry() {
		return journalEntry;
	}

	public OffsetDateTime getEntryDate() {
		return entryDate;
	}

	public LedgerAccount getAccount() {
		return account;
	}

	public BigDecimal getDebit() {
		return debit;
	}

	public BigDecimal getCredit() {
		return credit;
	}

	public UUID getContractId() {
		return contractId;
	}
}
