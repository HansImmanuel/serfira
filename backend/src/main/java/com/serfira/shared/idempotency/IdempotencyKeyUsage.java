package com.serfira.shared.idempotency;

import com.serfira.shared.clock.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

/**
 * Which PII key ids the still-replayable request fingerprints were computed under (ADR-025), so an operator can
 * tell whether a key can be retired. A fingerprint is unexpired when {@code expires_at} is null or in the future:
 * exactly the rows {@link IdempotencyService} may still compare on a replay.
 */
@Service
public class IdempotencyKeyUsage {

	private final IdempotencyKeyRepository keys;
	private final Clock clock;

	public IdempotencyKeyUsage(IdempotencyKeyRepository keys, Clock clock) {
		this.keys = keys;
		this.clock = clock;
	}

	/** Unexpired claims by recorded key id; claims recorded before key ids (NULL) are counted as unlabelled. */
	@Transactional(readOnly = true)
	public FingerprintKeyUsage unexpired() {
		Map<String, Long> byKid = new HashMap<>();
		long unlabelled = 0L;
		for (IdempotencyKeyRepository.KidCount row : keys.countUnexpiredByRequestHashKid(clock.now())) {
			if (row.getKid() == null) {
				unlabelled += row.getTotal();
			} else {
				byKid.merge(row.getKid(), row.getTotal(), Long::sum);
			}
		}
		return new FingerprintKeyUsage(Map.copyOf(byKid), unlabelled);
	}

	/** {@code byKid} holds only non-null kids; {@code unlabelled} counts the NULL-kid rows. */
	public record FingerprintKeyUsage(Map<String, Long> byKid, long unlabelled) {
	}
}
