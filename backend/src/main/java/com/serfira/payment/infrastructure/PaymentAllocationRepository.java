package com.serfira.payment.infrastructure;

import com.serfira.payment.domain.PaymentAllocation;
import com.serfira.payment.domain.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Persistence for {@link PaymentAllocation} — append-only rows, so this repository only inserts and
 * aggregates (no update/delete path exists or is offered).
 */
public interface PaymentAllocationRepository extends JpaRepository<PaymentAllocation, UUID> {

	/**
	 * Active allocations per installment and component, in one query.
	 *
	 * <p>Filtered by payment status: the allocations of a voided payment stay in the table as history
	 * but no longer count as active (DM §1.8, Addendum §6), while V3's cap trigger counts every row —
	 * hence the explicit {@code status = POSTED} here (ADR-009 risk note).
	 */
	@Query("""
			select new com.serfira.payment.infrastructure.ActiveAllocationTotal(
				a.installmentId, a.type, sum(a.amount))
			from PaymentAllocation a
			where a.payment.status = :status
				and a.installmentId in :installmentIds
			group by a.installmentId, a.type
			""")
	List<ActiveAllocationTotal> sumActiveByInstallmentIds(@Param("status") PaymentStatus status,
			@Param("installmentIds") Collection<UUID> installmentIds);

	/** Every allocation row of one payment, for the void path (which installments it touched, its EXCESS). */
	List<PaymentAllocation> findByPaymentId(UUID paymentId);

	/**
	 * {@code Σ} allocation amount (every type, EXCESS included) over the allocations of one contract's
	 * payments in the given status, for the daily consistency check (F1, task T17, Addendum §7.1 check B,
	 * {@code ALLOCATION_PAYMENT}). Called with {@code status = POSTED}, so the allocations of a voided
	 * payment are excluded, matching {@link PaymentRepository#sumAmountByContractAndStatus}. By invariant 6
	 * (every rupiah of a payment is allocated, EXCESS included) the two sums must be equal.
	 * {@code coalesce} yields zero rather than {@code null} when the contract has no allocation in that
	 * status.
	 */
	@Query("""
			select coalesce(sum(a.amount), 0)
			from PaymentAllocation a
			where a.payment.contractId = :contractId and a.payment.status = :status
			""")
	BigDecimal sumAmountByContractAndStatus(@Param("contractId") UUID contractId,
			@Param("status") PaymentStatus status);

	/**
	 * Resolved amount (penalty + interest + principal, EXCESS excluded) per installment, summed over the
	 * allocations of payments in the given status, for a set of installments (E4 void, task T16). After the
	 * voided payment flips to {@code VOIDED} and the change is flushed, calling this with
	 * {@code status = POSTED} yields exactly what the <b>remaining</b> POSTED payments resolve — the
	 * absolute paid amount the void recomputes each installment to.
	 *
	 * <p>Only installments that still have a resolving POSTED allocation appear; an installment absent from
	 * the result has a recomputed paid amount of zero.
	 */
	@Query("""
			select new com.serfira.payment.infrastructure.ResolvedInstallmentTotal(
				a.installmentId, sum(a.amount))
			from PaymentAllocation a
			where a.payment.status = :status
				and a.type <> com.serfira.payment.domain.AllocationType.EXCESS
				and a.installmentId in :installmentIds
			group by a.installmentId
			""")
	List<ResolvedInstallmentTotal> sumResolvedByInstallmentIds(@Param("status") PaymentStatus status,
			@Param("installmentIds") Collection<UUID> installmentIds);

	/**
	 * The earliest {@code paid_at} among the payments in the given status whose non-EXCESS allocations
	 * resolve each installment (E4 void, task T16). Called with {@code status = POSTED} after the voided
	 * payment is flushed VOIDED, so it yields the first <b>surviving</b> resolving instant per installment,
	 * which the void writes back as the installment's {@code paid_at} (ADR-020 D1.3).
	 *
	 * <p>Only installments that still have a resolving POSTED allocation appear; an installment absent from
	 * the result is no longer resolved by any POSTED payment, so its {@code paid_at} is cleared.
	 */
	@Query("""
			select new com.serfira.payment.infrastructure.EarliestResolvingInstant(
				a.installmentId, min(a.payment.paidAt))
			from PaymentAllocation a
			where a.payment.status = :status
				and a.type <> com.serfira.payment.domain.AllocationType.EXCESS
				and a.installmentId in :installmentIds
			group by a.installmentId
			""")
	List<EarliestResolvingInstant> earliestResolvingInstantByInstallmentIds(@Param("status") PaymentStatus status,
			@Param("installmentIds") Collection<UUID> installmentIds);
}
