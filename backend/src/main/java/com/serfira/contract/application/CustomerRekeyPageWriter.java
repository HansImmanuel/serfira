package com.serfira.contract.application;

import com.serfira.contract.application.RekeyRowVerifier.RekeyedValues;
import com.serfira.contract.infrastructure.CustomerPiiRow;
import com.serfira.contract.infrastructure.CustomerPiiRows;
import com.serfira.shared.audit.AuditContext;
import com.serfira.shared.clock.Clock;
import com.serfira.shared.security.PiiHasher;
import com.serfira.shared.security.PiiSecuritySupport;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Re-keys one page of customers in its own transaction (T28b). Each row that is not on the active key is
 * re-encrypted and re-hashed, rewritten, read back and verified; a failed check throws, so the whole page rolls back
 * and no half-verified row is ever committed. A separate bean from {@link PiiRekeyJob} so the {@code REQUIRES_NEW}
 * boundary is a real proxy call.
 */
public class CustomerRekeyPageWriter {

	private final CustomerPiiRows rows;
	private final AuditContext auditContext;
	private final Clock clock;

	CustomerRekeyPageWriter(CustomerPiiRows rows, AuditContext auditContext, Clock clock) {
		this.rows = rows;
		this.auditContext = auditContext;
		this.clock = clock;
	}

	/** One page's tally. {@code lastScannedId} is the cursor for the next page, {@code null} when the page is empty. */
	public record PageResult(UUID lastScannedId, int scanned, int rekeyed) {
	}

	/**
	 * Re-keys up to {@code limit} customers after {@code after}, in id order.
	 *
	 * @throws PiiRekeyVerificationException when a rewritten row does not read back as written; the page rolls back
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public PageResult rekeyPage(UUID after, int limit) {
		List<CustomerPiiRow> page = rows.pageAfter(after, limit);
		int rekeyed = 0;
		for (CustomerPiiRow row : page) {
			String nik = PiiSecuritySupport.decrypt(row.nikEnvelope());
			String phone = PiiSecuritySupport.decrypt(row.phoneEnvelope());
			if (CustomerKeyCensus.onActiveKey(row, nik, phone)) {
				continue;
			}
			RekeyedValues expected = new RekeyedValues(row.id(), nik, phone, PiiHasher.hashNik(nik),
					PiiHasher.hashPhone(phone));
			int updated = rows.rewrite(row.id(), PiiSecuritySupport.encrypt(nik), expected.nikHash(),
					PiiSecuritySupport.encrypt(phone), expected.phoneLookup(), clock.now(), auditContext.actorId());
			if (updated != 1) {
				throw new PiiRekeyVerificationException(row.id(), "the rewrite updated " + updated + " rows, not 1");
			}
			CustomerPiiRow reread = rows.findById(row.id()).orElseThrow(
					() -> new PiiRekeyVerificationException(row.id(), "the row is gone after its rewrite"));
			RekeyRowVerifier.verify(expected, reread);
			rekeyed++;
		}
		UUID lastScannedId = page.isEmpty() ? null : page.get(page.size() - 1).id();
		return new PageResult(lastScannedId, page.size(), rekeyed);
	}
}
