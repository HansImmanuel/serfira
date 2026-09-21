package com.serfira.contract.api;

import com.serfira.contract.domain.AssetType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Financed asset payload of a contract creation request (PRD C-1, DM §1.2, FE §2.3).
 *
 * <p>{@code serialNo} and {@code plateNo} identify the physical item (unique when present) and are
 * what create resolves an existing asset row by. When both identify an existing row, that row is
 * reused unchanged — the request's brand/model are not applied to it.
 */
public record CreateAssetRequest(
		@NotNull AssetType assetType,
		@NotBlank @Size(max = 80) String brand,
		@NotBlank @Size(max = 80) String model,
		@Size(max = 40) String serialNo,
		@Size(max = 20) String plateNo) {
}