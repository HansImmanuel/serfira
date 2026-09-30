package com.serfira.contract.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T6 — {@link Installment#markOverdue(LocalDate, int)}, the aging transition table for every source status
 * (DM §1.4, ADR-013 A-4/A-5).
 *
 * <p>Fixture: period 1 due 2026-02-28, grace 3, so 2026-03-03 is the last free day and 2026-03-04 is the
 * boundary day on which the condition first holds.
 *
 * <p>{@code SETTLED}/{@code WRITTEN_OFF} have no mutator yet (stories E2/E6), so those two source states are
 * set on the {@code status} field by reflection; every other state is reached through the real mutators.
 * The real-database variants run in {@code InstallmentAgingServiceIT}.
 */
class InstallmentMarkOverdueTest {

	private static final LocalDate DUE_DATE = LocalDate.of(2026, 2, 28);
	private static final int GRACE_DAYS = 3;
	private static final LocalDate LAST_FREE_DAY = LocalDate.of(2026, 3, 3);
	private static final LocalDate BOUNDARY_DAY = LocalDate.of(2026, 3, 4);
	private static final OffsetDateTime PAID_AT = OffsetDateTime.of(2026, 3, 1, 9, 0, 0, 0, ZoneOffset.ofHours(7));

	@Test
	void pendingBecomesOverdueOnTheBoundaryDayButNotTheDayBefore() {
		Installment installment = installment();

		assertThat(installment.markOverdue(LAST_FREE_DAY, GRACE_DAYS)).isFalse();
		assertThat(installment.getStatus()).isEqualTo(InstallmentStatus.PENDING);

		assertThat(installment.markOverdue(BOUNDARY_DAY, GRACE_DAYS)).isTrue();
		assertThat(installment.getStatus()).isEqualTo(InstallmentStatus.OVERDUE);
	}

	@Test
	void partiallyPaidBecomesOverdueOnTheBoundaryDayAndKeepsItsPaymentHistory() {
		Installment installment = installment();
		installment.applyPayment(new BigDecimal("1000000.00"), PAID_AT);

		assertThat(installment.markOverdue(LAST_FREE_DAY, GRACE_DAYS)).isFalse();
		assertThat(installment.getStatus()).isEqualTo(InstallmentStatus.PARTIALLY_PAID);

		assertThat(installment.markOverdue(BOUNDARY_DAY, GRACE_DAYS)).isTrue();
		assertThat(installment.getStatus()).isEqualTo(InstallmentStatus.OVERDUE);
		// V4: OVERDUE may carry paid_at; aging never clears the payment history.
		assertThat(installment.getPaidAt()).isEqualTo(PAID_AT);
		assertThat(installment.getPaidAmount()).isEqualByComparingTo("1000000.00");
	}

	@Test
	void aLaterBusinessDateAlsoMarksAPendingInstallmentOverdue() {
		Installment installment = installment();

		assertThat(installment.markOverdue(LocalDate.of(2026, 5, 31), GRACE_DAYS)).isTrue();
		assertThat(installment.getStatus()).isEqualTo(InstallmentStatus.OVERDUE);
	}

	@Test
	void anAlreadyOverdueInstallmentIsNotRewritten() {
		Installment installment = installment();
		installment.markOverdue(BOUNDARY_DAY, GRACE_DAYS);

		assertThat(installment.markOverdue(BOUNDARY_DAY, GRACE_DAYS)).isFalse();
		assertThat(installment.markOverdue(BOUNDARY_DAY.plusDays(1), GRACE_DAYS)).isFalse();
		assertThat(installment.getStatus()).isEqualTo(InstallmentStatus.OVERDUE);
	}

	@Test
	void agingNeverMovesAnInstallmentOutOfOverdueEvenBeforeTheBoundary() {
		Installment installment = installment();
		installment.markOverdue(BOUNDARY_DAY, GRACE_DAYS);

		// A backfill for an earlier date must not "un-age" the installment.
		assertThat(installment.markOverdue(LAST_FREE_DAY, GRACE_DAYS)).isFalse();
		assertThat(installment.getStatus()).isEqualTo(InstallmentStatus.OVERDUE);
	}

	@Test
	void aPaidInstallmentIsNeverAged() {
		Installment installment = installment();
		installment.applyPayment(new BigDecimal("1333333.33"), PAID_AT);

		assertThat(installment.markOverdue(BOUNDARY_DAY, GRACE_DAYS)).isFalse();
		assertThat(installment.getStatus()).isEqualTo(InstallmentStatus.PAID);
	}

	@ParameterizedTest
	@EnumSource(value = InstallmentStatus.class, names = {"SETTLED", "WRITTEN_OFF"})
	void nonPaymentResolutionsAreNeverAgedEvenWithAnOutstandingRemainder(InstallmentStatus resolved)
			throws ReflectiveOperationException {
		Installment installment = installment();
		// Deliberately leaves outstanding > 0 so only the status guard can stop the transition.
		forceStatus(installment, resolved);

		assertThat(installment.markOverdue(BOUNDARY_DAY, GRACE_DAYS)).isFalse();
		assertThat(installment.getStatus()).isEqualTo(resolved);
	}

	@Test
	void anInstallmentWithNothingOutstandingIsNotAged() {
		// Defensive guard: a zero-amount line has no receivable, so it is never in arrears.
		Installment installment = new Installment(contract(), 1, DUE_DATE, new BigDecimal("0.00"),
				new BigDecimal("0.00"));

		assertThat(installment.markOverdue(BOUNDARY_DAY, GRACE_DAYS)).isFalse();
		assertThat(installment.getStatus()).isEqualTo(InstallmentStatus.PENDING);
	}

	@Test
	void theContractGracePeriodDecidesTheBoundary() {
		Installment installment = installment();

		assertThat(installment.markOverdue(LocalDate.of(2026, 3, 1), 0)).isTrue();
		assertThat(installment.getStatus()).isEqualTo(InstallmentStatus.OVERDUE);
	}

	@Test
	void rejectsAMissingBusinessDateAndANegativeGracePeriod() {
		Installment installment = installment();

		assertThatThrownBy(() -> installment.markOverdue(null, GRACE_DAYS))
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> installment.markOverdue(BOUNDARY_DAY, -1))
				.isInstanceOf(IllegalArgumentException.class);
		assertThat(installment.getStatus()).isEqualTo(InstallmentStatus.PENDING);
	}

	private static Installment installment() {
		return new Installment(contract(), 1, DUE_DATE, new BigDecimal("1333333.33"), new BigDecimal("240000.00"));
	}

	private static void forceStatus(Installment installment, InstallmentStatus status)
			throws ReflectiveOperationException {
		Field field = Installment.class.getDeclaredField("status");
		field.setAccessible(true);
		field.set(installment, status);
	}

	/** Detached aggregate — enough for a pure value test; nothing is persisted here. */
	private static Contract contract() {
		Customer customer = new Customer("Budi Santoso", "3171012501900001", "nik-hash",
				"08123456789", "phone-lookup", "Jakarta");
		Asset asset = new Asset(AssetType.MOTORCYCLE, "Honda", "Beat", null, "B1234XY");
		return new Contract("MF-202601-0001", customer, asset, new BigDecimal("20000000.00"),
				new BigDecimal("16000000.00"), new BigDecimal("4000000.00"), 12, InterestScheme.FLAT,
				new BigDecimal("0.0150"), GRACE_DAYS, new BigDecimal("0.0010"), LocalDate.of(2026, 1, 31),
				"idem-key");
	}
}
