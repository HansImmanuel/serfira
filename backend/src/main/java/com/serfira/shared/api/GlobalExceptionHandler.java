package com.serfira.shared.api;

import com.serfira.shared.error.ApiError;
import com.serfira.shared.error.ApiResponse;
import com.serfira.shared.error.ErrorCode;
import com.serfira.shared.error.SerfiraException;
import jakarta.persistence.OptimisticLockException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.stream.Collectors;

/**
 * Single translation point for all API failures (TECH SPEC §2.2). Every error leaves through the standard
 * {@link ApiResponse} envelope; internal exceptions never leak stack traces or implementation details.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(SerfiraException.class)
	public ResponseEntity<ApiResponse<Void>> handleSerfira(SerfiraException ex) {
		return error(ex.status(), ApiError.of(ex.code(), ex.getMessage()));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
		String details = ex.getBindingResult().getFieldErrors().stream()
				.map(fieldError -> fieldError.getField() + " " + fieldError.getDefaultMessage())
				.collect(Collectors.joining("; "));
		return error(HttpStatus.BAD_REQUEST, ApiError.of(ErrorCode.VALIDATION_ERROR, "Validation failed: " + details));
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
		return error(HttpStatus.BAD_REQUEST,
				ApiError.of(ErrorCode.VALIDATION_ERROR, "Invalid request parameter '" + ex.getName() + "'"));
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ApiResponse<Void>> handleUnreadableBody(HttpMessageNotReadableException ex) {
		return error(HttpStatus.BAD_REQUEST,
				ApiError.of(ErrorCode.VALIDATION_ERROR, "Request body could not be read: "
						+ ex.getMostSpecificCause().getMessage()));
	}

	@ExceptionHandler({OptimisticLockException.class, ObjectOptimisticLockingFailureException.class})
	public ResponseEntity<ApiResponse<Void>> handleOptimisticLock(Exception ex) {
		return error(HttpStatus.CONFLICT,
				ApiError.of(ErrorCode.CONCURRENT_MODIFICATION, "Record changed concurrently; refresh and retry"));
	}

	@ExceptionHandler(DataIntegrityViolationException.class)
	public ResponseEntity<ApiResponse<Void>> handleDataIntegrity(DataIntegrityViolationException ex) {
		LOGGER.warn("Data integrity violation translated to 409 CONFLICT", ex);
		return error(HttpStatus.CONFLICT, ApiError.of(ErrorCode.CONFLICT, "Request conflicts with the current state"));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex) {
		LOGGER.error("Unhandled exception mapped to 500 INTERNAL_ERROR", ex);
		return error(HttpStatus.INTERNAL_SERVER_ERROR, ApiError.of(ErrorCode.INTERNAL_ERROR, "Internal server error"));
	}

	private ResponseEntity<ApiResponse<Void>> error(HttpStatus status, ApiError apiError) {
		return ResponseEntity.status(status).body(ApiResponse.fail(apiError));
	}
}