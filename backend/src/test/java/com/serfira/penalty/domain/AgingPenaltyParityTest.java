package com.serfira.penalty.domain;

import com.serfira.contract.domain.InstallmentAging;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T6 — pins the aging calendar ({@code contract.domain.InstallmentAging}) to the penalty calendar
 * ({@link PenaltyTerms}). ADR-013 A-4 defines DPD-start as the first chargeable penalty day; the formula lives
 * in both modules because {@code contract} may not depend on {@code penalty}, so this test is what keeps the
 * two definitions identical.
 */
class AgingPenaltyParityTest {

	private static final List<LocalDate> DUE_DATES = List.of(
			LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 28), LocalDate.of(2026, 3, 31),
			LocalDate.of(2026, 4, 30), LocalDate.of(2026, 12, 31), LocalDate.of(2028, 2, 28),
			LocalDate.of(2028, 2, 29));
	private static final List<Integer> GRACE_DAYS = List.of(0, 1, 3, 7, 30);

	@Test
	void theFirstOverdueDateIsTheFirstChargeableDate() {
		for (LocalDate dueDate : DUE_DATES) {
			for (int grace : GRACE_DAYS) {
				assertThat(InstallmentAging.firstOverdueDate(dueDate, grace))
						.as("due %s grace %d", dueDate, grace)
						.isEqualTo(terms(dueDate, grace).firstChargeableDate());
			}
		}
	}

	@Test
	void dpdEqualsDaysLateOnEveryChargeableDateAndIsZeroBefore() {
		for (LocalDate dueDate : DUE_DATES) {
			for (int grace : GRACE_DAYS) {
				PenaltyTerms terms = terms(dueDate, grace);
				LocalDate first = terms.firstChargeableDate();
				assertThat(InstallmentAging.daysPastDue(dueDate, grace, first.minusDays(1)))
						.as("due %s grace %d, day before first chargeable", dueDate, grace)
						.isZero();
				for (int offset = 0; offset < 100; offset++) {
					LocalDate date = first.plusDays(offset);
					assertThat(InstallmentAging.daysPastDue(dueDate, grace, date))
							.as("due %s grace %d on %s", dueDate, grace, date)
							.isEqualTo(terms.daysLate(date));
				}
			}
		}
	}

	private static PenaltyTerms terms(LocalDate dueDate, int grace) {
		return new PenaltyTerms(dueDate, grace, new BigDecimal("0.0010"), new BigDecimal("1000.00"),
				dueDate.plusYears(1), Set.of());
	}
}
