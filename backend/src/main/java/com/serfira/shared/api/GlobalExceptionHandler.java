package com.serfira.shared.api;

import com.serfira.shared.error.ApiError;
import com.serfira.shared.error.ErrorCode;
import com.serfira.shared.error.SerfiraException;
import jakarta.persistence.OptimisticLockException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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

	@ExceptionHandler(ConstraintViolationException.class)
	public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException ex) {
		return error(HttpStatus.BAD_REQUEST,
				ApiError.of(ErrorCode.VALIDATION_ERROR, "Validation failed: " + ex.getMessage()));
	}

	@ExceptionHandler(HandlerMethodValidationException.class)
	public ResponseEntity<ApiResponse<Void>> handleHandlerMethodValidation(HandlerMethodValidationException ex) {
		return error(HttpStatus.BAD_REQUEST,
				ApiError.of(ErrorCode.VALIDATION_ERROR, "Request parameter validation failed"));
	}

	@ExceptionHandler(NoResourceFoundException.class)
	public ResponseEntity<ApiResponse<Void>> handleNoResourceFound(NoResourceFoundException ex) {
		return error(HttpStatus.NOT_FOUND, ApiError.of(ErrorCode.NOT_FOUND, "Resource not found"));
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
		return error(HttpStatus.BAD_REQUEST,
				ApiError.of(ErrorCode.VALIDATION_ERROR, "Invalid request parameter '" + ex.getName() + "'"));
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ApiResponse<Void>> handleUnreadableBody(HttpMessageNotReadableException ex) {
		// Deliberately generic: the specific Jackson cause may reference internal types.
		LOGGER.warn("Unreadable request body rejected");
		return error(HttpStatus.BAD_REQUEST,
				ApiError.of(ErrorCode.VALIDATION_ERROR, "Request body could not be read"));
	}

	@ExceptionHandler({OptimisticLockException.class, ObjectOptimisticLockingFailureException.class})
	public ResponseEntity<ApiResponse<Void>> handleOptimisticLock(Exception ex) {
		return error(HttpStatus.CONFLICT,
				ApiError.of(ErrorCode.CONCURRENT_MODIFICATION, "Record changed concurrently; refresh and retry"));
	}

	/**
	 * Method-level security denials (e.g. {@code @PreAuthorize}) thrown inside MVC handling reach this
	 * advice. Filter-chain denials are resolved by Spring Security before MVC and return the same envelope
	 * via the resource-server entry points in {@code ResourceServerSecurityConfiguration}.
	 */
	@ExceptionHandler(AccessDeniedException.class)
	public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException ex) {
		return error(HttpStatus.FORBIDDEN, ApiError.of(ErrorCode.FORBIDDEN, "Access denied"));
	}

	/** Framework-raised HTTP errors (unknown route, missing converter, …) keep their proper status. */
	@ExceptionHandler(ErrorResponseException.class)
	public ResponseEntity<ApiResponse<Void>> handleErrorResponse(ErrorResponseException ex) {
		return error(HttpStatus.valueOf(ex.getStatusCode().value()),
				ApiError.of(errorCodeFor(HttpStatus.valueOf(ex.getStatusCode().value())),
						"Request could not be processed"));
	}

	@ExceptionHandler(MissingServletRequestParameterException.class)
	public ResponseEntity<ApiResponse<Void>> handleMissingParameter(MissingServletRequestParameterException ex) {
		return error(HttpStatus.BAD_REQUEST,
				ApiError.of(ErrorCode.VALIDATION_ERROR, "Missing required parameter '" + ex.getParameterName() + "'"));
	}

	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
		return error(HttpStatus.METHOD_NOT_ALLOWED,
				ApiError.of(ErrorCode.VALIDATION_ERROR, "HTTP method not supported for this endpoint"));
	}

	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	public ResponseEntity<ApiResponse<Void>> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex) {
		return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
				ApiError.of(ErrorCode.VALIDATION_ERROR, "Unsupported request media type"));
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

	private static ErrorCode errorCodeFor(HttpStatus status) {
		if (status.is4xxClientError()) {
			return switch (status.value()) {
				case 403 -> ErrorCode.FORBIDDEN;
				case 404 -> ErrorCode.NOT_FOUND;
				default -> ErrorCode.VALIDATION_ERROR;
			};
		}
		return ErrorCode.INTERNAL_ERROR;
	}
}