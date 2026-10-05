package com.serfira.contract.infrastructure;

import com.serfira.contract.domain.Contract;
import com.serfira.contract.domain.ContractStatus;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.domain.Specification;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for {@link Contract}. Uniqueness of {@code contract_no} is enforced by the database,
 * as is "one live contract per (customer, asset)" (V7 {@code uq_contract_live_asset}).
 *
 * <p>The list/detail reads fetch {@code customer} and {@code asset} together with the contract so a
 * response can be assembled without per-row lazy loads.
 */
public interface ContractRepository extends JpaRepository<Contract, UUID>, JpaSpecificationExecutor<Contract> {

	Optional<Contract> findByContractNo(String contractNo);

	/** Used by the invariant-18 guard: is there already a live contract for this customer + asset? */
	boolean existsByCustomerIdAndAssetIdAndStatusIn(UUID customerId, UUID assetId, Collection<ContractStatus> statuses);

	@EntityGraph(attributePaths = {"customer", "asset"})
	@Override
	Page<Contract> findAll(Specification<Contract> spec, Pageable pageable);

	@EntityGraph(attributePaths = {"customer", "asset"})
	@Query("select c from Contract c where c.id = :id")
	Optional<Contract> findDetailById(@Param("id") UUID id);

	/**
	 * Keyset page for background servicing (CR-08): ACTIVE ids after a cursor, ascending, capped by
	 * {@code limit}. Never exposes entities across modules. The first page passes the all-zero UUID sentinel
	 * ({@code 00000000-0000-0000-0000-000000000000}) so the {@code afterId} bind is always a typed non-null
	 * UUID (avoids the X-13 {@code 42P18} untyped-null failure); that sentinel is strictly less than every
	 * {@code GenerationType.UUID}/{@code gen_random_uuid()} id, so it selects the true first page.
	 */
	@Query("select c.id from Contract c where c.status = :status and c.id > :afterId order by c.id")
	List<UUID> findActiveContractIdsAfter(@Param("status") ContractStatus status,
			@Param("afterId") UUID afterId, Limit limit);

	boolean existsByIdAndStatus(UUID id, ContractStatus status);
}