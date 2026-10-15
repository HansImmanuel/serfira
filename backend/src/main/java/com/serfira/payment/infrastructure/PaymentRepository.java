package com.serfira.payment.infrastructure;

import com.serfira.payment.domain.Payment;
import com.serfira.payment.domain.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Persistence for {@link Payment}. Uniqueness of {@code payment_no} and "one POSTED payment per
 * idempotency key" (V1 {@code uq_payment_idempotency}) are enforced by the database.
 */
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

	/**
	 * {@code Σ} payment amount for one contract in the given status, for the daily consistency check (F1,
	 * task T17, Addendum §7.1 check A/B). Called with {@code status = POSTED}, so a voided payment is
	 * excluded — the active view both the allocation total and the ledger reversal of a void agree with.
	 * {@code coalesce} yields zero rather than {@code null} when the contract has no payment in that status.
	 */
	@Query("""
			select coalesce(sum(p.amount), 0)
			from Payment p
			where p.contractId = :contractId and p.status = :status
			""")
	BigDecimal sumAmountByContractAndStatus(@Param("contractId") UUID contractId,
			@Param("status") PaymentStatus status);
}
