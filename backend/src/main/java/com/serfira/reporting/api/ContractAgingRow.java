package com.serfira.reporting.api;

import java.util.UUID;

/**
 * One contract's line in the aging report: the contract's identity plus its per-bucket breakdown
 * (06_FRONTEND_SPEC.md §2.9). Only ACTIVE contracts with at least one owed installment appear.
 *
 * @param contractId contract id
 * @param contractNo contract business number ({@code MF-YYYYMM-XXXX})
 * @param customerName the customer's full name (T37), stored unencrypted (Addendum §9); NIK and phone never
 *                   appear on a report
 * @param buckets    the contract's outstanding split across the five delinquency buckets
 */
public record ContractAgingRow(UUID contractId, String contractNo, String customerName,
		AgingBucketsResponse buckets) {
}
