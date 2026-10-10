package com.permision;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.permision.api.PermissionRequest;
import com.permision.service.PermissionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class PermissionServiceTests {
	private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
	private final PermissionService service = new PermissionService(jdbcTemplate, new SimpleMeterRegistry());

	@Test
	void acceptsValidIdentifiersAndExecutesQuotedGrant() {
		when(jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?",
				Integer.class, "permission_sample", "test_table")).thenReturn(1);

		service.apply(new PermissionRequest("test-reader", "permission_sample", "test_table",
				List.of("select")), "GRANT");

		verify(jdbcTemplate).execute(
				"GRANT SELECT ON `permission_sample`.`test_table` TO 'test-reader'@'%'");
	}

	@Test
	void usesMariaDbRevokeSyntax() {
		when(jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?",
				Integer.class, "permission_sample", "test_table")).thenReturn(1);

		service.apply(new PermissionRequest("test-reader", "permission_sample", "test_table",
				List.of("SELECT")), "REVOKE");

		verify(jdbcTemplate).execute(
				"REVOKE SELECT ON `permission_sample`.`test_table` FROM 'test-reader'@'%'");
	}

	@Test
	void rejectsSqlInjectionInIdentifiersBeforeExecutingSql() {
		IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
				() -> service.apply(new PermissionRequest("test-reader",
						"permission_sample` ; DROP DATABASE permission_sample --",
						"test_table", List.of("SELECT")), "GRANT"));

		assertTrue(exception.getMessage().contains("valid MariaDB identifiers"));
		verify(jdbcTemplate, never()).execute(org.mockito.ArgumentMatchers.anyString());
	}

	@Test
	void rejectsSqlInjectionInAccountName() {
		assertThrows(IllegalArgumentException.class,
				() -> service.apply(new PermissionRequest("reader'@'%' --",
						"permission_sample", "test_table", List.of("SELECT")), "GRANT"));

		verify(jdbcTemplate, never()).execute(org.mockito.ArgumentMatchers.anyString());
	}

	@Test
	void rejectsPermissionsOutsideTheAllowList() {
		assertThrows(IllegalArgumentException.class,
				() -> service.apply(new PermissionRequest("test-reader",
						"permission_sample", "test_table", List.of("SELECT; DROP DATABASE")), "GRANT"));

		verify(jdbcTemplate, never()).execute(org.mockito.ArgumentMatchers.anyString());
	}

	@Test
	void serializesOperationsForTheSamePrincipal() throws Exception {
		CountDownLatch firstQueryEntered = new CountDownLatch(1);
		CountDownLatch releaseFirstQuery = new CountDownLatch(1);
		AtomicInteger queryCount = new AtomicInteger();
		AtomicInteger activeQueries = new AtomicInteger();
		AtomicInteger maximumConcurrentQueries = new AtomicInteger();
		when(jdbcTemplate.queryForObject(
				eq("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?"),
				eq(Integer.class), eq("permission_sample"), eq("test_table")))
				.thenAnswer(invocation -> {
					int active = activeQueries.incrementAndGet();
					maximumConcurrentQueries.accumulateAndGet(active, Math::max);
					if (queryCount.incrementAndGet() == 1) {
						firstQueryEntered.countDown();
						releaseFirstQuery.await();
					}
					activeQueries.decrementAndGet();
					return 1;
				});

		ExecutorService workers = Executors.newFixedThreadPool(2);
		AtomicReference<Thread> secondThread = new AtomicReference<>();
		CountDownLatch secondStarted = new CountDownLatch(1);
		try {
			Future<?> first = workers.submit(() -> service.apply(
					new PermissionRequest("same-principal", "permission_sample", "test_table",
							List.of("SELECT")), "GRANT"));
			assertTrue(firstQueryEntered.await(5, TimeUnit.SECONDS));
			Future<?> second = workers.submit(() -> {
				secondThread.set(Thread.currentThread());
				secondStarted.countDown();
				service.apply(new PermissionRequest("same-principal", "permission_sample", "test_table",
						List.of("INSERT")), "GRANT");
			});
			assertTrue(secondStarted.await(5, TimeUnit.SECONDS));

			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
			while (secondThread.get().getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
				Thread.yield();
			}
			assertEquals(Thread.State.WAITING, secondThread.get().getState());
			assertEquals(1, queryCount.get(), "the second operation must wait before entering JDBC");

			releaseFirstQuery.countDown();
			first.get(5, TimeUnit.SECONDS);
			second.get(5, TimeUnit.SECONDS);
			assertEquals(1, maximumConcurrentQueries.get());
		} finally {
			releaseFirstQuery.countDown();
			workers.shutdownNow();
		}
	}
}
