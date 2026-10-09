package com.serfira.settlement.application;

import com.serfira.contract.application.SettlementContractSnapshot;
import com.serfira.contract.application.SettlementInstallment;
import com.serfira.penalty.application.InstallmentEffectivePenalty;
import com.serfira.settlement.domain.SettlementInstallmentInput;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure mapping tests for {@link SettlementQuoteCommandService#toEngineInstallments} (T12, PR #9 review
 * finding 1). The quote derives {@code resolvedBeyondPenalty} — the money already applied past penalty —
 * from each installment's money-based {@code resolvedAmount} and its paid penalty, so a penalty
 * <b>adjustment</b> (a waiver, which is not money) must never be subtracted again: it is already carried
 * by {@code effectivePenalty}. Subtracting it a second time understated interest/principal and overstated
 * {@code gross_amount}/{@code cash_due}, so a fully-paid installment with a fully-waived penalty was quoted
 * as still owing the waived sum.
 */
class SettlementQuoteCommandServiceMappingTest {

	private static final LocalDate START = LocalDate.of(2026, 1, 15);
	private static final UUID INSTALLMENT_ID = UUID.randomUUID();

	@Test
	void aFullyPaidInstallmentWithaFullyWaivedPenaltyHasNothingResolvedAgainstTheWaiver() {
		// Gross penalty 100 fully waived; interest+principal (1050) paid in cash; no penalty paid by money.
		BigDecimal paid = money("1050.00"); // interest 50 + principal 1000
		SettlementContractSnapshot snapshot = snapshotWith(installment(paid));
		Map<UUID, InstallmentEffectivePenalty> penalty = Map.of(INSTALLMENT_ID,
				effectivePenalty(money("100.00"), money("100.00"), money("0.00")));

		SettlementInstallmentInput mapped = only(SettlementQuoteCommandService
				.toEngineInstallments(snapshot, penalty));

		// The waiver (adjustment=100) is NOT money and must not reduce the resolved-beyond-penalty amount.
		assertThat(mapped.resolvedBeyondPenalty()).isEqualByComparingTo("1050.00");
		assertThat(mapped.effectivePenalty()).isEqualByComparingTo("0.00");
	}

	@Test
	void aPartiallyWaivedPenaltyWithaPenaltyPaymentCountsOnlyThePaidPenalty() {
		// Gross 100: 40 waived, 60 paid by money; interest+principal (1050) also paid.
		// resolvedAmount(paid) = 60 penalty + 1050 = 1110; resolvedBeyondPenalty must be 1050.
		BigDecimal paid = money("1110.00");
		SettlementContractSnapshot snapshot = snapshotWith(installment(paid));
		Map<UUID, InstallmentEffectivePenalty> penalty = Map.of(INSTALLMENT_ID,
				effectivePenalty(money("100.00"), money("40.00"), money("60.00")));

		SettlementInstallmentInput mapped = only(SettlementQuoteCommandService
				.toEngineInstallments(snapshot, penalty));

		assertThat(mapped.resolvedBeyondPenalty()).isEqualByComparingTo("1050.00");
		assertThat(mapped.effectivePenalty()).isEqualByComparingTo("0.00");
	}

	@Test
	void anUntouchedInstallmentWithNoPenaltyRecordResolvesNothing() {
		SettlementContractSnapshot snapshot = snapshotWith(installment(money("0.00")));

		SettlementInstallmentInput mapped = only(SettlementQuoteCommandService
				.toEngineInstallments(snapshot, Map.of()));

		assertThat(mapped.resolvedBeyondPenalty()).isEqualByComparingTo("0.00");
		assertThat(mapped.effectivePenalty()).isEqualByComparingTo("0.00");
	}

	private static SettlementInstallmentInput only(List<SettlementInstallmentInput> inputs) {
		assertThat(inputs).hasSize(1);
		return inputs.get(0);
	}

	private static SettlementContractSnapshot snapshotWith(SettlementInstallment installment) {
		return new SettlementContractSnapshot(UUID.randomUUID(), "MF-202601-0001", 0L, START,
				List.of(installment));
	}

	private static SettlementInstallment installment(BigDecimal resolvedAmount) {
		return new SettlementInstallment(INSTALLMENT_ID, 1, START.plusMonths(1), false,
				money("1000.00"), money("50.00"), money("50.00"), resolvedAmount);
	}

	private static InstallmentEffectivePenalty effectivePenalty(BigDecimal gross, BigDecimal adjustment,
			BigDecimal paidPenalty) {
		BigDecimal effective = gross.subtract(adjustment).subtract(paidPenalty);
		effective = effective.signum() < 0 ? money("0.00") : effective;
		return new InstallmentEffectivePenalty(INSTALLMENT_ID, gross, adjustment, paidPenalty, effective);
	}

	private static BigDecimal money(String value) {
		return new BigDecimal(value);
	}
}
