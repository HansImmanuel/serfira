package com.serfira.contract.infrastructure;

import com.serfira.contract.domain.Contract;
import com.serfira.contract.domain.ContractStatus;
import com.serfira.contract.domain.Customer;

import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;

import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Filter predicates for the contract list (PRD C-4, FE §2.2): status and a free-text query over
 * contract number or customer name.
 *
 * <p>Queries are built explicitly rather than by derived method names so the list endpoint can opt
 * in and out of each filter without an explosion of repository methods.
 */
public final class ContractSpecifications {

	private static final char LIKE_ESCAPE = '\\';

	private ContractSpecifications() {
	}

	/**
	 * Composes the supported filters. Both arguments are optional: {@code null} status and a
	 * blank query each contribute no predicate (so an unfiltered list returns everything).
	 *
	 * <p>The customer join is to-one, so the derived count query stays correct.
	 */
	public static Specification<Contract> matching(ContractStatus status, String query) {
		return (root, criteriaQuery, criteriaBuilder) -> {
			List<Predicate> predicates = new ArrayList<>();
			if (status != null) {
				predicates.add(criteriaBuilder.equal(root.get("status"), status));
			}
			String needle = query == null ? null : query.trim();
			if (needle != null && !needle.isEmpty()) {
				Join<Contract, Customer> customer = root.join("customer", JoinType.INNER);
				String pattern = "%" + escape(needle.toLowerCase(Locale.ROOT)) + "%";
				predicates.add(criteriaBuilder.or(
						criteriaBuilder.like(criteriaBuilder.lower(root.get("contractNo")), pattern, LIKE_ESCAPE),
						criteriaBuilder.like(criteriaBuilder.lower(customer.get("fullName")), pattern, LIKE_ESCAPE)));
			}
			return predicates.isEmpty()
					? criteriaBuilder.conjunction()
					: criteriaBuilder.and(predicates.toArray(Predicate[]::new));
		};
	}

	/** Escapes LIKE wildcards in user input so a search for {@code 100%} is a literal search. */
	private static String escape(String value) {
		return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}
}