package com.serfira.settlement.api;

import com.serfira.settlement.domain.SettlementQuote;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Response of {@code POST /api/v1/settlements/quote} (E1, task T12, ADR-018 D6). The priced quote the
 * customer must pay to settle early: every component column plus the derived {@code gross_amount} and
 * {@code cash_due}, the TTL cutoff, and the snapshotted contract version T13 revalidates against.
 *
 * <p>Wire names are snake_case via the global Jackson configuration; the record fields stay camelCase.
 *
 * @param quoteId               the quote id (passed to {@code POST /settlements} at execution, T13)
 * @param quoteNo               business number ({@code Q-YYYYMMDD-XXXX})
 * @param contractId            the contract being settled
 * @param status                lifecycle status; {@code QUOTED} on creation
 * @param quotedAt              when the quote was priced (Asia/Jakarta)
 * @param validUntil            TTL cutoff — the quote is executable only until this instant
 * @param contractVersion       the contract version the components were priced against (D9)
 * @param outstandingPrincipal  Σ unpaid principal, incl. future periods
 * @param unpaidBilledInterest  Σ recognized-but-unpaid interest
 * @param accruedInterest       running interest of the active period (ACT/30, D7)
 * @param penaltyOutstanding    Σ effective remaining penalty (D8)
 * @param rebateAmount          the rebate on unearned future interest (D2)
 * @param adminFee              the flat settlement admin fee
 * @param availableCredit       the contract's available customer credit, snapshotted (not consumed at T12)
 * @param creditUsed            credit consumed by the quote — always {@code 0} at T12
 * @param grossAmount           Σ of all charged components (D6)
 * @param cashDue               {@code gross_amount − credit_used} (D6)
 */
public record SettlementQuoteResponse(UUID quoteId, String quoteNo, UUID contractId, String status,
		OffsetDateTime quotedAt, OffsetDateTime validUntil, long contractVersion,
		BigDecimal outstandingPrincipal, BigDecimal unpaidBilledInterest, BigDecimal accruedInterest,
		BigDecimal penaltyOutstanding, BigDecimal rebateAmount, BigDecimal adminFee,
		BigDecimal availableCredit, BigDecimal creditUsed, BigDecimal grossAmount, BigDecimal cashDue) {

	public static SettlementQuoteResponse from(SettlementQuote quote) {
		return new SettlementQuoteResponse(quote.getId(), quote.getQuoteNo(), quote.getContractId(),
				quote.getStatus().name(), quote.getQuotedAt(), quote.getValidUntil(), quote.getContractVersion(),
				quote.getOutstandingPrincipal(), quote.getUnpaidBilledInterest(), quote.getAccruedInterest(),
				quote.getPenaltyOutstanding(), quote.getRebateAmount(), quote.getAdminFee(),
				quote.getAvailableCredit(), quote.getCreditUsed(), quote.getGrossAmount(), quote.getCashDue());
	}
}
