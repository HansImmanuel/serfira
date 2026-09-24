package com.serfira.contract.application;

import com.serfira.contract.domain.InstallmentStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Read-only penalty view of one installment, exposed to the {@code penalty} module (DM §1.4/§1.9,
 * ADR-012).
 *
 * <p>{@code penaltyBase} is the documented "pokok+bunga yang belum dibayar" of TS §4.3
 * ({@code InstallmentBalance.penaltyBase}) and {@code status} is carried because a {@code SETTLED} /
 * {@code WRITTEN_OFF} installment may never accrue more penalty — every other decision about which days are
 * chargeable belongs to the caller's {@code PenaltyCalculator}.
 *
 * @param installmentId installment the values belong to
 * @param periodNo      1-based period number, used in the accrual journal description
 * @param dueDate       due date; lateness and the grace window are measured from here
 * @param penaltyBase   unpaid principal + unpaid recognized interest, {@code >= 0}
 * @param status        resolution state; {@code SETTLED}/{@code WRITTEN_OFF} are never charged
 */
public record InstallmentPenalty(UUID installmentId, int periodNo, LocalDate dueDate, BigDecimal penaltyBase,
		InstallmentStatus status) {
}
