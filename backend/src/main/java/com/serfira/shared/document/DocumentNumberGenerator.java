package com.serfira.shared.document;

import com.serfira.shared.clock.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Generates business-facing document numbers (TECH SPEC §1.1, story A4).
 *
 * <p>Each call runs in a transaction: the counter row for the current (type, period) is locked
 * with {@code SELECT … FOR UPDATE}, incremented, and flushed, so concurrent callers cannot observe
 * the same sequence value. A gap after a rollback is accepted (Addendum §1.4); uniqueness is the
 * contract, not gapless numbering.
 */
@Service
public class DocumentNumberGenerator {

	private final DocumentNumberCounterRepository repository;
	private final Clock clock;

	public DocumentNumberGenerator(DocumentNumberCounterRepository repository, Clock clock) {
		this.repository = repository;
		this.clock = clock;
	}

	@Transactional
	public String next(DocumentType type) {
		OffsetDateTime now = clock.now();
		String key = DocumentNumberFormatter.counterKey(type, now);

		DocumentNumberCounter counter = repository.findForUpdateByCounterKey(key).orElseGet(() -> {
			repository.insertIfAbsent(UUID.randomUUID(), key, now);
			return repository.findForUpdateByCounterKey(key)
					.orElseThrow(() -> new IllegalStateException("counter row disappeared for key '" + key + "'"));
		});

		int sequence = counter.nextValue();
		repository.saveAndFlush(counter);
		return DocumentNumberFormatter.format(type, now, sequence);
	}
}