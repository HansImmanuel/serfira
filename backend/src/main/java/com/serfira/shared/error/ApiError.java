package com.serfira.shared.error;

/**
 * Error part of the standard Serfira envelope (TECH SPEC §2.2): {@code {"error": {"code": "...", "message": "..."}}}.
 *
 * @param code    stable machine-readable code (see {@link ErrorCode})
 * @param message human-readable explanation, safe for clients and logs
 */
public record ApiError(String code, String message) {

	public static ApiError of(ErrorCode code, String message) {
		return new ApiError(code.name(), message);
	}
}