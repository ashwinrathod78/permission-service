package com.permision.api;

import com.permision.service.PermissionService;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
	private final PermissionService permissionService;

	public ApiExceptionHandler(PermissionService permissionService) {
		this.permissionService = permissionService;
	}

	@ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
	public ResponseEntity<Map<String, String>> validation(Exception exception) {
		if (exception instanceof MethodArgumentNotValidException) {
			permissionService.recordError("validation");
		}
		String message = exception instanceof MethodArgumentNotValidException invalid
				? invalid.getBindingResult().getFieldErrors().stream().findFirst()
						.map(FieldError::getDefaultMessage).orElse("Request validation failed.")
				: exception.getMessage();
		return ResponseEntity.badRequest().body(Map.of("error", "validation", "message", safeMessage(message)));
	}

	@ExceptionHandler(DataAccessException.class)
	public ResponseEntity<Map<String, String>> sql(DataAccessException exception) {
		permissionService.recordError("sql");
		return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
				.body(Map.of("error", "sql", "message", "The database rejected the request."));
	}

	private String safeMessage(String message) {
		return message == null || message.isBlank() ? "Request validation failed." : message;
	}
}
