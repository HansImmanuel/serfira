package com.serfira.contract.api;

import com.serfira.contract.domain.Asset;
import com.serfira.contract.domain.AssetType;

import java.util.UUID;

/** Financed asset projection (never the JPA entity — TS §2.2). */
public record AssetResponse(UUID id, AssetType assetType, String brand, String model, String serialNo, String plateNo) {

	public static AssetResponse from(Asset asset) {
		return new AssetResponse(asset.getId(), asset.getAssetType(), asset.getBrand(), asset.getModel(),
				asset.getSerialNo(), asset.getPlateNo());
	}
}