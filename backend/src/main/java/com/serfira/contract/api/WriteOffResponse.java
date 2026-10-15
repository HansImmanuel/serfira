package com.serfira.contract.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Response of {@code POST /api/v1/contracts/{id}/write-off} (F5, task T19): the terminated contract and
 * what the write-off capped per installment.
 *
 * @param contractId        the written-off contract
 * @param contractNo        its business number
 * @param status            the contract status after the write-off ({@code TERMINATED})
 * @param writeOffReason    the recorded reason
 * @param writtenOffAt      business instant of the write-off
 * @param totalWrittenOff   total recognized receivable capped across all installments; scale-2 money
 * @param installments      the installments moved to {@code WRITTEN_OFF}, with the amount capped on each
 */
public record WriteOffResponse(UUID contractId, String contractNo, String status, String writeOffReason,
		OffsetDateTime writtenOffAt, BigDecimal totalWrittenOff, List<WrittenOffInstallment> installments) {

	public WriteOffResponse {
		installments = List.copyOf(installments);
	}

	/**
	 * One installment the write-off closed.
	 *
	 * @param installmentId  the installment id
	 * @param periodNo       its period number
	 * @param writtenOffAmount recognized receivable capped on this installment; scale-2 money, {@code >= 0}
	 */
	public record WrittenOffInstallment(UUID installmentId, int periodNo, BigDecimal writtenOffAmount) {
	}
}
