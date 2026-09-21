package com.serfira.contract.application;

import com.serfira.contract.api.ContractListItem;
import com.serfira.contract.api.ContractResponse;
import com.serfira.contract.api.InstallmentResponse;
import com.serfira.contract.domain.Contract;
import com.serfira.contract.domain.ContractStatus;
import com.serfira.contract.infrastructure.ContractInstallmentTotals;
import com.serfira.contract.infrastructure.ContractRepository;
import com.serfira.contract.infrastructure.ContractSpecifications;
import com.serfira.contract.infrastructure.InstallmentRepository;
import com.serfira.shared.api.PageResponse;
import com.serfira.shared.error.BadRequestException;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Read-only contract queries (PRD C-4): paged list with filters, detail with outstanding, and the
 * schedule of one contract.
 *
 * <p>Reads run in a read-only transaction, so the entity graph and any lazy association access happen
 * inside one consistent snapshot.
 */
@Service
@Transactional(readOnly = true)
public class ContractQueryService {

	/**
	 * Supported sort tokens, using the wire (snake_case) spelling documented for the API. Values are
	 * the entity property paths the database sorts on.
	 */
	private static final Map<String, String> SORTABLE_PROPERTIES = sortableProperties();

	private static final int MAX_PAGE_SIZE = 100;
	private static final String DEFAULT_SORT_PROPERTY = "created_at";

	private final ContractRepository contracts;
	private final InstallmentRepository installments;

	public ContractQueryService(ContractRepository contracts, InstallmentRepository installments) {
		this.contracts = contracts;
		this.installments = installments;
	}

	/**
	 * Paged contract list.
	 *
	 * @param status optional status filter
	 * @param query  optional free-text filter over contract number or customer name
	 * @param sort   {@code <property>} or {@code <property>,<asc|desc>}; defaults to
	 *               {@code created_at,desc} (FE §2.2)
	 * @throws BadRequestException for out-of-range paging or an unsupported sort
	 */
	public PageResponse<ContractListItem> list(ContractStatus status, String query, int page, int size, String sort) {
		Page<Contract> result = contracts.findAll(ContractSpecifications.matching(status, query),
				pageRequest(page, size, sort));
		Map<UUID, BigDecimal> outstanding = outstandingByContract(result.getContent());
		List<ContractListItem> items = result.getContent().stream()
				.map(contract -> ContractListItem.from(contract, outstanding.get(contract.getId())))
				.toList();
		return PageResponse.of(items, result.getNumber(), result.getSize(), result.getTotalElements(),
				result.getTotalPages());
	}

	/** Detail of one contract, with its outstanding. */
	public ContractResponse detail(UUID contractId) {
		Contract contract = contracts.findDetailById(contractId)
				.orElseThrow(() -> new ContractNotFoundException(contractId));
		return ContractResponse.from(contract, outstandingOf(contractId));
	}

	/** Schedule of one contract, oldest period first. An unknown contract is a 404, not an empty list. */
	public List<InstallmentResponse> installments(UUID contractId) {
		if (!contracts.existsById(contractId)) {
			throw new ContractNotFoundException(contractId);
		}
		return installments.findByContractIdOrderByPeriodNo(contractId).stream()
				.map(InstallmentResponse::from)
				.toList();
	}

	private static PageRequest pageRequest(int page, int size, String sort) {
		if (page < 0) {
			throw new BadRequestException("page must be >= 0");
		}
		if (size < 1 || size > MAX_PAGE_SIZE) {
			throw new BadRequestException("size must be between 1 and " + MAX_PAGE_SIZE);
		}
		return PageRequest.of(page, size, parseSort(sort));
	}

	/**
	 * Parses the documented (snake_case) sort tokens and maps them to entity properties. Unknown
	 * properties are rejected rather than passed to the persistence layer, so a typo cannot become a
	 * 500 — property names are never taken straight from the request.
	 */
	private static Sort parseSort(String sort) {
		if (sort == null || sort.isBlank()) {
			return Sort.by(Sort.Order.desc(SORTABLE_PROPERTIES.get(DEFAULT_SORT_PROPERTY)));
		}
		String[] parts = sort.split(",", -1);
		if (parts.length > 2) {
			throw new BadRequestException("sort must be '<property>' or '<property>,<asc|desc>'");
		}
		String property = parts[0].trim();
		String entityProperty = SORTABLE_PROPERTIES.get(property);
		if (entityProperty == null) {
			throw new BadRequestException("unsupported sort property '" + property + "'; supported: "
					+ String.join(", ", SORTABLE_PROPERTIES.keySet()));
		}
		Sort.Direction direction = Sort.Direction.ASC;
		if (parts.length == 2 && !parts[1].isBlank()) {
			direction = switch (parts[1].trim().toLowerCase(Locale.ROOT)) {
				case "asc" -> Sort.Direction.ASC;
				case "desc" -> Sort.Direction.DESC;
				default -> throw new BadRequestException("sort direction must be 'asc' or 'desc'");
			};
		}
		return Sort.by(new Sort.Order(direction, entityProperty));
	}

	/** One aggregate query for the whole page instead of a per-row lazy walk over installments. */
	private Map<UUID, BigDecimal> outstandingByContract(List<Contract> pageContent) {
		if (pageContent.isEmpty()) {
			return Map.of();
		}
		List<UUID> ids = pageContent.stream().map(Contract::getId).toList();
		return installments.sumTotalsByContractIds(ids).stream()
				.collect(Collectors.toMap(ContractInstallmentTotals::contractId,
						ContractInstallmentTotals::outstanding));
	}

	private BigDecimal outstandingOf(UUID contractId) {
		return installments.sumTotalsByContractId(contractId)
				.map(ContractInstallmentTotals::outstanding)
				.orElse(null);
	}

	private static Map<String, String> sortableProperties() {
		Map<String, String> properties = new LinkedHashMap<>();
		properties.put(DEFAULT_SORT_PROPERTY, "createdAt");
		properties.put("contract_no", "contractNo");
		properties.put("status", "status");
		// Order-preserving on purpose: the rejection message lists the supported tokens.
		return Collections.unmodifiableMap(properties);
	}
}