package com.serfira.ledger.domain;

import com.serfira.shared.audit.ImmutableAuditable;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A posted journal entry — one financial event, at least two balanced lines
 * (03_DOMAIN_MODEL.md §1.13, ADR-002, ADR-008).
 *
 * <p><b>Immutable.</b> V1's {@code trg_journal_entry_immutable} trigger rejects UPDATE/DELETE, so this
 * entity has no setters, no {@code @Version}, and no update audit columns; corrections are new entries
 * whose {@code reversalOfId} points at the original (story E4).
 *
 * <p>{@code entryDate} is the business date of the event and {@code postedAt} the instant the entry was
 * recorded, both final at insert. {@code reversalOfId} is kept as a plain id: a ledger entry is never
 * traversed as an entity graph while posting.
 */
@Entity
@Table(name = "journal_entry")
public class JournalEntry extends ImmutableAuditable {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "entry_date", nullable = false, columnDefinition = "timestamptz")
	private OffsetDateTime entryDate;

	@Enumerated(EnumType.STRING)
	@Column(name = "ref_type", nullable = false, length = 40)
	private LedgerRefType refType;

	@Column(name = "ref_id", nullable = false)
	private UUID refId;

	@Column(name = "description", length = 255)
	private String description;

	@Column(name = "reversal_of_id")
	private UUID reversalOfId;

	@Column(name = "posted_at", nullable = false, columnDefinition = "timestamptz")
	private OffsetDateTime postedAt;

	@OneToMany(mappedBy = "journalEntry", cascade = CascadeType.PERSIST, fetch = FetchType.LAZY)
	private List<JournalLine> lines = new ArrayList<>();

	protected JournalEntry() {
		// JPA
	}

	/**
	 * Materializes an already-validated {@link LedgerPosting} as a posted entry. Lines inherit the
	 * entry's business date, because {@code journal_line.entry_date} is denormalized so the documented
	 * {@code (account_code, entry_date)} index can be served (V1).
	 *
	 * @param postedAt recording instant from the application clock; final at insert
	 */
	public JournalEntry(LedgerPosting posting, OffsetDateTime postedAt) {
		Objects.requireNonNull(posting, "posting");
		this.entryDate = posting.entryDate();
		this.refType = posting.refType();
		this.refId = posting.refId();
		this.description = posting.description();
		this.reversalOfId = posting.reversalOfId();
		this.postedAt = Objects.requireNonNull(postedAt, "postedAt");
		posting.lines().forEach(line -> lines.add(new JournalLine(this, entryDate, line)));
	}

	public UUID getId() {
		return id;
	}

	public OffsetDateTime getEntryDate() {
		return entryDate;
	}

	public LedgerRefType getRefType() {
		return refType;
	}

	public UUID getRefId() {
		return refId;
	}

	public String getDescription() {
		return description;
	}

	public UUID getReversalOfId() {
		return reversalOfId;
	}

	public OffsetDateTime getPostedAt() {
		return postedAt;
	}

	/** The entry's lines. Unmodifiable: posted lines are never mutated. */
	public List<JournalLine> getLines() {
		return Collections.unmodifiableList(lines);
	}
}
