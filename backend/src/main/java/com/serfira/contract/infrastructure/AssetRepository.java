package com.serfira.contract.infrastructure;

import com.serfira.contract.domain.Asset;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Persistence for {@link Asset}.
 */
public interface AssetRepository extends JpaRepository<Asset, UUID> {
}