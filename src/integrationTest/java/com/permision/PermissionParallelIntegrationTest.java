package com.permision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.permision.api.PermissionRequest;
import com.permision.service.PermissionService;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@Import(PermissionParallelIntegrationTest.Instrumentation.class)
class PermissionParallelIntegrationTest {
	private static final String TEST_DB_PASSWORD = UUID.randomUUID().toString();

	@Container
	static final MariaDBContainer<?> mariaDb = new MariaDBContainer<>("mariadb:11.4.5")
			.withDatabaseName("permission_metadata")
			.withUsername("permission_service")
			.withPassword(TEST_DB_PASSWORD)
			.withCopyFileToContainer(MountableFile.forClasspathResource("grant-test-user.sql"),
					"/docker-entrypoint-initdb.d/zz-grant-test-user.sql");

	@DynamicPropertySource
	static void configureProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", mariaDb::getJdbcUrl);
		registry.add("spring.datasource.username", () -> "permission_service");
		registry.add("spring.datasource.password", () -> TEST_DB_PASSWORD);
		registry.add("spring.flyway.url", mariaDb::getJdbcUrl);
		registry.add("spring.flyway.user", mariaDb::getUsername);
		registry.add("spring.flyway.password", () -> TEST_DB_PASSWORD);
		registry.add("spring.flyway.placeholders.managementUser", () -> "permission_service");
		registry.add("spring.flyway.placeholders.managementPassword", () -> TEST_DB_PASSWORD);
		registry.add("permission.bulk.timeout", () -> "20s");
	}

	@Autowired
	MockMvc http;

	@Autowired
	InstrumentedPermissionService instrumentedService;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@BeforeEach
	void createPrincipals() throws Exception {
		instrumentedService.resetMaxActive();
		for (int index = 0; index < 12; index++) {
			jdbcTemplate.execute("DROP USER IF EXISTS 'bulk-user-" + index + "'@'%'");
			jdbcTemplate.execute("CREATE USER IF NOT EXISTS 'bulk-user-" + index + "'@'%'");
		}
	}

	@Test
	void bulkRunsFourRealDatabaseWorkersInParallelAndLeavesEveryPrivilegeInExpectedState() throws Exception {
		List<String> selectGrants = new ArrayList<>();
		List<String> insertGrants = new ArrayList<>();
		List<String> insertRevokes = new ArrayList<>();
		for (int index = 0; index < 12; index++) {
			String username = "bulk-user-" + index;
			selectGrants.add(operation("GRANT", username, "SELECT"));
			insertGrants.add(operation("GRANT", username, "INSERT"));
			insertRevokes.add(operation("REVOKE", username, "INSERT"));
		}

		String firstGrant = postBulk(selectGrants);
		String secondGrant = postBulk(insertGrants);
		String revoke = postBulk(insertRevokes);

		assertTrue(firstGrant.contains("\"succeeded\":12"));
		assertTrue(secondGrant.contains("\"succeeded\":12"));
		assertTrue(revoke.contains("\"succeeded\":12"));
		assertEquals(4, instrumentedService.maxObservedConcurrency(),
				"the barrier makes this assertion deterministic; a serial implementation times out");
		for (int index = 0; index < 12; index++) {
			String privilegeResponse = http.perform(get("/permissions/bulk-user-" + index))
					.andExpect(status().isOk())
					.andReturn().getResponse().getContentAsString();
			assertTrue(privilegeResponse.contains("\"privilege\":\"SELECT\""));
			assertFalse(privilegeResponse.contains("\"privilege\":\"INSERT\""));
		}
	}

	@Test
	void endpointRejectsIdentifierInjectionAndLeavesSchemaUntouched() throws Exception {
		var response = http.perform(post("/permissions/grant")
				.contentType(APPLICATION_JSON)
				.content("{\"username\":\"test-reader\",\"database\":\"permission_sample` ; "
						+ "DROP DATABASE permission_sample --\",\"table\":\"test_table\","
						+ "\"permissions\":[\"SELECT\"]}"))
				.andReturn().getResponse();

		assertEquals(400, response.getStatus());
		assertEquals(1, jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?",
				Integer.class, "permission_sample", "test_table"));
	}

	@Test
	void bulkReturnsPerItemPartialFailureAndKeepsSuccessfulChanges() throws Exception {
		String response = postBulk(List.of(
				operation("GRANT", "bulk-user-0", "INVALID"),
				operation("GRANT", "bulk-user-1", "SELECT"),
				operation("GRANT", "bulk-user-2", "SELECT"),
				operation("GRANT", "bulk-user-3", "SELECT")));

		assertTrue(response.contains("\"succeeded\":3"));
		assertTrue(response.contains("\"failed\":1"));
		assertTrue(response.contains("\"status\":\"validation_error\""));
		String failedPrincipalPrivileges = http.perform(get("/permissions/bulk-user-0"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		assertFalse(failedPrincipalPrivileges.contains("\"privilege\":\"SELECT\""));
		for (int index = 1; index < 4; index++) {
			String privileges = http.perform(get("/permissions/bulk-user-" + index))
					.andExpect(status().isOk())
					.andReturn().getResponse().getContentAsString();
			assertTrue(privileges.contains("\"privilege\":\"SELECT\""));
		}
	}

	private String operation(String action, String username, String permission) {
		return "{\"action\":\"" + action + "\",\"request\":{\"username\":\"" + username
				+ "\",\"database\":\"permission_sample\",\"table\":\"test_table\","
				+ "\"permissions\":[\"" + permission + "\"]}}";
	}

	private String postBulk(List<String> operations) throws Exception {
		return http.perform(post("/permissions/bulk")
				.contentType(APPLICATION_JSON)
				.content("{\"operations\":[" + String.join(",", operations) + "]}"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
	}

	@TestConfiguration
	static class Instrumentation {
		@Bean
		@Primary
		InstrumentedPermissionService instrumentedPermissionService(
				@Qualifier("permissionService") PermissionService delegate, MeterRegistry registry) {
			return new InstrumentedPermissionService(delegate, registry);
		}
	}

	static class InstrumentedPermissionService extends PermissionService {
		private final PermissionService delegate;
		private final CyclicBarrier parallelWorkers = new CyclicBarrier(4);
		private final AtomicInteger active = new AtomicInteger();
		private final AtomicInteger maximum = new AtomicInteger();

		InstrumentedPermissionService(PermissionService delegate, MeterRegistry registry) {
			super(null, registry);
			this.delegate = delegate;
		}

		@Override
		public void apply(PermissionRequest request, String action) {
			int nowActive = active.incrementAndGet();
			maximum.accumulateAndGet(nowActive, Math::max);
			try {
				parallelWorkers.await(10, TimeUnit.SECONDS);
				delegate.apply(request, action);
			} catch (IllegalArgumentException exception) {
				throw exception;
			} catch (Exception exception) {
				throw new IllegalStateException("Four bulk workers did not arrive concurrently.", exception);
			} finally {
				active.decrementAndGet();
			}
		}

		@Override
		public List<com.permision.api.PrivilegeResult> privilegesFor(String username) {
			return delegate.privilegesFor(username);
		}

		@Override
		public void recordError(String category) {
			delegate.recordError(category);
		}

		int maxObservedConcurrency() {
			return maximum.get();
		}

		void resetMaxActive() {
			maximum.set(0);
		}
	}
}
