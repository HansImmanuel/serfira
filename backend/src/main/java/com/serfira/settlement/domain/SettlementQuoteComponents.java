package com.serfira.settlement.domain;

import java.math.BigDecimal;

/**
 * The priced components {@link SettlementQuoteEngine} produces (E1, task T12, ADR-018 D6). These map
 * one-to-one onto the {@code settlement_quote} columns the quote persists; {@code futureInterestGross}
 * and {@code futureInterestCharged} are intermediate figures the engine exposes so a test can assert the
 * D6 identities ({@code futureInterestCharged = futureInterestGross − rebateAmount}, and
 * {@code grossAmount}/{@code cashDue} derivations).
 *
 * @param outstandingPrincipal  Σ unpaid principal, incl. future periods
 * @param unpaidBilledInterest  Σ recognized-but-unpaid interest (real {@code PIUTANG_BUNGA})
 * @param accruedInterest       running interest of the single earliest active period (ACT/30, D7)
 * @param penaltyOutstanding    Σ effective remaining penalty (invariant 9, D8)
 * @param futureInterestGross   Σ unearned future interest, net of {@code accruedInterest} (D2)
 * @param rebateAmount          {@code round(rebateRate × futureInterestGross, HALF_EVEN, 2)} (D2)
 * @param futureInterestCharged {@code futureInterestGross − rebateAmount}, the half the customer pays (D2)
 * @param adminFee              {@code SETTLEMENT_ADMIN_FEE}
 * @param availableCredit       the contract's available customer credit, snapshotted (not consumed)
 * @param creditUsed            credit consumed by the quote — always {@code 0} at T12 (consumption is T13)
 * @param grossAmount           Σ of all charged components (D6)
 * @param cashDue               {@code grossAmount − creditUsed} (D6)
 */
public record SettlementQuoteComponents(BigDecimal outstandingPrincipal, BigDecimal unpaidBilledInterest,
		BigDecimal accruedInterest, BigDecimal penaltyOutstanding, BigDecimal futureInterestGross,
		BigDecimal rebateAmount, BigDecimal futureInterestCharged, BigDecimal adminFee,
		BigDecimal availableCredit, BigDecimal creditUsed, BigDecimal grossAmount, BigDecimal cashDue) {
}
