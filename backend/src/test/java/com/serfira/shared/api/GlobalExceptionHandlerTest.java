package com.serfira.shared.api;

import com.serfira.shared.api.ApiResponse;
import com.serfira.shared.error.ApiError;
import com.serfira.shared.error.ConflictException;
import com.serfira.shared.error.NotFoundException;
import com.serfira.shared.error.SerfiraException;
import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

	private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

	@Test
	void serfiraExceptionMapsToEnvelopeWithStatusAndCode() {
		ResponseEntity<ApiResponse<Void>> response = handler.handleSerfira(new NotFoundException("contract not found"));

		assertThat(response.getStatusCode().value()).isEqualTo(404);
		assertThat(response.getBody().data()).isNull();
		assertThat(response.getBody().error().code()).isEqualTo("NOT_FOUND");
		assertThat(response.getBody().error().message()).isEqualTo("contract not found");
	}

	@Test
	void conflictMapsTo409() {
		ResponseEntity<ApiResponse<Void>> response = handler.handleSerfira(new ConflictException("duplicate key"));

		assertThat(response.getStatusCode().value()).isEqualTo(409);
		assertThat(response.getBody().error().code()).isEqualTo("CONFLICT");
	}

	@Test
	void unhandledExceptionIsObfuscated() {
		ResponseEntity<ApiResponse<Void>> response = handler.handleUnexpected(new IllegalStateException("secret detail"));

		assertThat(response.getStatusCode().value()).isEqualTo(500);
		assertThat(response.getBody().error().code()).isEqualTo("INTERNAL_ERROR");
		assertThat(response.getBody().error().message()).doesNotContain("secret detail");
	}

	@Test
	void beanValidationMapsTo400ValidationError() throws Exception {
		BindingResult result = new org.springframework.validation.BeanPropertyBindingResult(new Object(), "cmd");
		result.addError(new FieldError("cmd", "amount", "must not be null"));
		org.springframework.core.MethodParameter parameter = new org.springframework.core.MethodParameter(
				GlobalExceptionHandler.class.getMethod("handleSerfira", SerfiraException.class), 0);
		MethodArgumentNotValidException ex = new MethodArgumentNotValidException(parameter, result);

		ResponseEntity<ApiResponse<Void>> response = handler.handleValidation(ex);

		assertThat(response.getStatusCode().value()).isEqualTo(400);
		assertThat(response.getBody().error().code()).isEqualTo("VALIDATION_ERROR");
		assertThat(response.getBody().error().message()).contains("amount");
	}

	@Test
	void optimisticLockMapsTo409ConcurrentModification() {
		ResponseEntity<ApiResponse<Void>> response = handler.handleOptimisticLock(new OptimisticLockException("stale"));

		assertThat(response.getStatusCode().value()).isEqualTo(409);
		assertThat(response.getBody().error().code()).isEqualTo("CONCURRENT_MODIFICATION");
	}

	@Test
	void accessDeniedMapsTo403Forbidden() {
		ResponseEntity<ApiResponse<Void>> response = handler.handleAccessDenied(
				new AccessDeniedException("denied detail"));

		assertThat(response.getStatusCode().value()).isEqualTo(403);
		assertThat(response.getBody().error().code()).isEqualTo("FORBIDDEN");
		// the raw denial reason is never echoed to the client
		assertThat(response.getBody().error().message()).doesNotContain("denied detail");
	}

	@Test
	void frameworkHttpErrorKeepsItsStatus() {
		ErrorResponseException ex = new ErrorResponseException(HttpStatus.NOT_FOUND);

		ResponseEntity<ApiResponse<Void>> response = handler.handleErrorResponse(ex);

		assertThat(response.getStatusCode().value()).isEqualTo(404);
		assertThat(response.getBody().error().code()).isEqualTo("NOT_FOUND");
	}

	@Test
	void frameworkClientErrorDefaultsToValidationError() {
		ResponseEntity<ApiResponse<Void>> response = handler.handleErrorResponse(
				new ErrorResponseException(HttpStatus.METHOD_NOT_ALLOWED));

		assertThat(response.getStatusCode().value()).isEqualTo(405);
		assertThat(response.getBody().error().code()).isEqualTo("VALIDATION_ERROR");
	}

	@Test
	void methodNotSupportedMapsTo405() {
		ResponseEntity<ApiResponse<Void>> response = handler.handleMethodNotSupported(
				new HttpRequestMethodNotSupportedException("DELETE"));

		assertThat(response.getStatusCode().value()).isEqualTo(405);
		assertThat(response.getBody().error().code()).isEqualTo("VALIDATION_ERROR");
	}

	@Test
	void dataIntegrityViolationMapsTo409() {
		ResponseEntity<ApiResponse<Void>> response = handler.handleDataIntegrity(
				new DataIntegrityViolationException("duplicate key value violates unique constraint"));

		assertThat(response.getStatusCode().value()).isEqualTo(409);
		assertThat(response.getBody().error().code()).isEqualTo("CONFLICT");
		assertThat(response.getBody().error().message()).doesNotContain("duplicate key");
	}

	@Test
	void responseEnvelopeSuccessfulShapeCanBeConstructed() {
		ApiResponse<String> ok = ApiResponse.ok("abc");
		assertThat(ok.data()).isEqualTo("abc");
		assertThat(ok.error()).isNull();
	}
}