package com.serfira.contract.infrastructure;

import com.serfira.contract.domain.Asset;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for {@link Asset}.
 *
 * <p>The financed item is identified by {@code serial_no} or, failing that, {@code plate_no} — both
 * are unique when present (partial unique indexes in V1). Create resolves an existing row through
 * these lookups and reuses it, exactly like the customer lookup uses {@code nik_hash}
 * (invariant 18, 03_DOMAIN_MODEL.md §3).
 */
public interface AssetRepository extends JpaRepository<Asset, UUID> {

	Optional<Asset> findBySerialNo(String serialNo);

	Optional<Asset> findByPlateNo(String plateNo);
}