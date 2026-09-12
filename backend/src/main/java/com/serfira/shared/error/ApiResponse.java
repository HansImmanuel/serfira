package com.serfira.shared.error;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Standard Serfira response envelope (TECH SPEC §2.2):
 *
 * <pre>{@code
 * { "data": ..., "error": null }
 * { "data": null, "error": { "code": "PAYMENT_NOT_FOUND", "message": "..." } }
 * }</pre>
 *
 * @param data  payload on success
 * @param error error on failure
 * @param <T>   payload type
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ApiResponse<T>(T data, ApiError error) {

	public static <T> ApiResponse<T> ok(T data) {
		return new ApiResponse<>(data, null);
	}

	public static <T> ApiResponse<T> fail(ApiError error) {
		return new ApiResponse<>(null, error);
	}
}