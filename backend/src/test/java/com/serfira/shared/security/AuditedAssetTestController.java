package com.serfira.shared.security;

import java.util.Map;
import java.util.UUID;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.serfira.contract.domain.Asset;
import com.serfira.contract.domain.AssetType;
import com.serfira.contract.infrastructure.AssetRepository;
import com.serfira.shared.api.ApiResponse;

/**
 * Test-only endpoint (Spring Security 7 resource-server IT) whose write goes through the real
 * {@code Auditable} {@code @PrePersist} path, so the IT can assert that {@code created_by} is the
 * JWT subject rather than the SYSTEM user. Lives in the test sources and is picked up by
 * component scanning only during tests.
 */
@RestController
@RequestMapping("/api/v1/__test-audit")
public class AuditedAssetTestController {

	private final AssetRepository assetRepository;

	public AuditedAssetTestController(AssetRepository assetRepository) {
		this.assetRepository = assetRepository;
	}

	@PostMapping("/assets")
	public ApiResponse<Map<String, UUID>> createAsset() {
		Asset asset = new Asset(AssetType.MOTORCYCLE, "Honda", "Beat", null, null);
		assetRepository.save(asset);
		return ApiResponse.ok(Map.of("assetId", asset.getId()));
	}
}
