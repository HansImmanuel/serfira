package com.serfira.shared.api;

import java.util.List;

/**
 * Page envelope for list endpoints (TECH SPEC §2.2 pagination: {@code page}, {@code size}, {@code sort}).
 *
 * <p>Serialized with the project-wide snake_case naming strategy, so the wire fields are
 * {@code content}, {@code page}, {@code size}, {@code total_elements}, {@code total_pages}.
 *
 * @param content       the page's items, already mapped to API DTOs
 * @param page          0-based page index
 * @param size          requested page size
 * @param totalElements total matching rows across all pages
 * @param totalPages    number of pages at this size
 */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

	public static <T> PageResponse<T> of(List<T> content, int page, int size, long totalElements, int totalPages) {
		return new PageResponse<>(List.copyOf(content), page, size, totalElements, totalPages);
	}
}