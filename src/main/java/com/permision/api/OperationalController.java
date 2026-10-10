package com.permision.api;

import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class OperationalController {
	private final JdbcTemplate jdbcTemplate;
	private final MeterRegistry meterRegistry;

	public OperationalController(JdbcTemplate jdbcTemplate, MeterRegistry meterRegistry) {
		this.jdbcTemplate = jdbcTemplate;
		this.meterRegistry = meterRegistry;
	}

	@GetMapping("/health")
	@Operation(summary = "Check process liveness", description = "Returns UP while the service process is running; does not verify database connectivity.")
	@ApiResponse(responseCode = "200", description = "Service is alive")
	public HealthResponse health() {
		return new HealthResponse("UP");
	}

	@GetMapping("/ready")
	@Operation(summary = "Check database readiness", description = "Runs SELECT 1 against MariaDB and returns 503 if the database cannot be reached.")
	@ApiResponse(responseCode = "200", description = "Service and database are ready")
	@ApiResponse(responseCode = "503", description = "Database is unavailable")
	public HealthResponse readiness() {
		try {
			jdbcTemplate.queryForObject("SELECT 1", Integer.class);
			return new HealthResponse("UP");
		} catch (DataAccessException exception) {
			meterRegistry.counter("permission.errors", "class", "infrastructure").increment();
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Database is unavailable.");
		}
	}

	@ResponseStatus(HttpStatus.OK)
	public record HealthResponse(String status) {
	}
}
