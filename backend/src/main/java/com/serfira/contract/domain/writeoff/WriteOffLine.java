package com.serfira.contract.domain.writeoff;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The receivable the write-off caps on one installment, split by component (F5, task T19). The three
 * component amounts feed the Cr lines of the single {@code WRITE_OFF} journal entry
 * (Cr {@code PIUTANG_POKOK}/{@code PIUTANG_BUNGA}/{@code PIUTANG_DENDA}); their sum ({@code total}) is the
 * amount written off onto the installment's {@code written_off_amount}.
 *
 * <p>All amounts are scale-2 money, {@code >= 0}. A component that nets to zero stays zero (no journal
 * line is emitted for it), so a fully-future installment with no recognized receivable yields a line whose
 * {@code total} is 0 — the status still moves to WRITTEN_OFF but no money is credited.
 *
 * @param installmentId the installment this receivable belongs to
 * @param periodNo      its period number
 * @param penalty       remaining penalty receivable capped (Cr {@code PIUTANG_DENDA})
 * @param interest      unpaid recognized interest capped (Cr {@code PIUTANG_BUNGA})
 * @param principal     unpaid principal capped (Cr {@code PIUTANG_POKOK})
 */
public record WriteOffLine(UUID installmentId, int periodNo, BigDecimal penalty, BigDecimal interest,
		BigDecimal principal) {

	/** Total recognized receivable capped on this installment: {@code penalty + interest + principal}. */
	public BigDecimal total() {
		return penalty.add(interest).add(principal);
	}
}
