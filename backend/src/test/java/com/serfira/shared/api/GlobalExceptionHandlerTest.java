package com.serfira.shared.api;

import com.serfira.shared.error.ApiResponse;
import com.serfira.shared.error.ConflictException;
import com.serfira.shared.error.NotFoundException;
import com.serfira.shared.error.SerfiraException;
import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
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
	void responseEnvelopeSuccessfulShapeCanBeConstructed() {
		com.serfira.shared.error.ApiResponse<String> ok = com.serfira.shared.error.ApiResponse.ok("abc");
		assertThat(ok.data()).isEqualTo("abc");
		assertThat(ok.error()).isNull();
	}
}