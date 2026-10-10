package com.permision.service;

import com.permision.api.BulkPermissionRequest;
import com.permision.api.BulkPermissionResult;
import com.permision.api.PermissionOperation;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service
public class BulkPermissionService {
	private static final Logger log = LoggerFactory.getLogger(BulkPermissionService.class);
	private final PermissionService permissionService;
	private final ThreadPoolExecutor executor;
	private final Counter rejectedTasks;
	private final int maxItems;
	private final Duration timeout;

	public BulkPermissionService(PermissionService permissionService, MeterRegistry meterRegistry,
			@Value("${permission.bulk.threads:4}") int threadCount,
			@Value("${permission.bulk.queue-capacity:32}") int queueCapacity,
			@Value("${permission.bulk.max-items:100}") int maxItems,
			@Value("${permission.bulk.timeout:10s}") Duration timeout) {
		if (threadCount < 1 || queueCapacity < 1 || maxItems < 1 || timeout.isNegative() || timeout.isZero()) {
			throw new IllegalArgumentException("Bulk executor settings must be positive.");
		}
		this.permissionService = permissionService;
		this.maxItems = maxItems;
		this.timeout = timeout;
		rejectedTasks = meterRegistry.counter("permission.bulk.executor.rejected");
		AtomicInteger threadId = new AtomicInteger();
		ThreadFactory factory = task -> {
			Thread thread = new Thread(task, "permission-bulk-" + threadId.incrementAndGet());
			thread.setDaemon(false);
			return thread;
		};
		executor = new ThreadPoolExecutor(threadCount, threadCount, 0, TimeUnit.MILLISECONDS,
				new ArrayBlockingQueue<>(queueCapacity), factory, new ThreadPoolExecutor.AbortPolicy());
		meterRegistry.gauge("permission.bulk.executor.active", executor, ThreadPoolExecutor::getActiveCount);
		meterRegistry.gauge("permission.bulk.executor.queue", executor, pool -> pool.getQueue().size());
	}

	public BulkPermissionResult apply(BulkPermissionRequest request) {
		List<PermissionOperation> operations = request.operations();
		if (operations.size() > maxItems) {
			permissionService.recordError("validation");
			throw new IllegalArgumentException("Bulk request exceeds the maximum of " + maxItems + " items.");
		}

		List<BulkPermissionResult.ItemResult> results = new ArrayList<>(operations.size());
		CompletionService<IndexedResult> completion = new ExecutorCompletionService<>(executor);
		Map<Future<IndexedResult>, Integer> pending = new HashMap<>();
		String correlationId = MDC.get("correlationId");
		for (int index = 0; index < operations.size(); index++) {
			PermissionOperation operation = operations.get(index);
			int itemIndex = index;
			try {
				Future<IndexedResult> future = completion.submit(() -> runOne(itemIndex, operation, correlationId));
				pending.put(future, itemIndex);
				results.add(null);
			} catch (RejectedExecutionException exception) {
				meterRegistryRejected();
				results.add(item(index, operation, "rejected", "Bulk executor is at capacity."));
			}
		}

		long deadline = System.nanoTime() + timeout.toNanos();
		boolean timedOut = false;
		while (!pending.isEmpty()) {
			long remaining = deadline - System.nanoTime();
			if (remaining <= 0) {
				timedOut = true;
				break;
			}
			try {
				Future<IndexedResult> completed = completion.poll(remaining, TimeUnit.NANOSECONDS);
				if (completed == null) {
					timedOut = true;
					break;
				}
				int index = pending.remove(completed);
				try {
					results.set(index, completed.get().result());
				} catch (ExecutionException | java.util.concurrent.CancellationException exception) {
					permissionService.recordError("infrastructure");
					results.set(index, item(index, operations.get(index), "infrastructure_error",
							"Permission change failed because of an infrastructure error."));
				}
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				timedOut = true;
				break;
			}
		}

		if (timedOut) {
			for (Map.Entry<Future<IndexedResult>, Integer> entry : pending.entrySet()) {
				PermissionOperation operation = operations.get(entry.getValue());
				boolean cancelled = entry.getKey().cancel(true);
				String status = cancelled ? (Thread.currentThread().isInterrupted() ? "cancelled" : "timed_out") : "timed_out";
				results.set(entry.getValue(), item(entry.getValue(), operation, status,
						cancelled ? "Bulk operation deadline exceeded; task cancellation was requested."
								: "Bulk operation deadline exceeded."));
				if (cancelled) {
					permissionService.recordError("infrastructure");
				}
			}
		}

		int succeeded = count(results, "succeeded");
		int failed = (int) results.stream().filter(result -> result != null
				&& ("validation_error".equals(result.status()) || "sql_error".equals(result.status())
						|| "infrastructure_error".equals(result.status()) || "rejected".equals(result.status())))
				.count();
		int timedOutCount = count(results, "timed_out");
		int cancelledCount = count(results, "cancelled");
		return new BulkPermissionResult(List.copyOf(results), succeeded, failed, timedOutCount, cancelledCount);
	}

	private IndexedResult runOne(int index, PermissionOperation operation, String correlationId) {
		if (correlationId != null) {
			MDC.put("correlationId", correlationId);
		}
		try {
			permissionService.apply(operation.request(), operation.action().name());
			return new IndexedResult(index, item(index, operation, "succeeded", "Permission change applied."));
		} catch (IllegalArgumentException exception) {
			return new IndexedResult(index, item(index, operation, "validation_error", exception.getMessage()));
		} catch (DataAccessException exception) {
			permissionService.recordError("sql");
			return new IndexedResult(index, item(index, operation, "sql_error", "Database rejected the permission change."));
		} catch (Exception exception) {
			log.warn("Permission bulk item failed action={} username={} category=infrastructure",
					operation.action(), operation.request().username(), exception);
			permissionService.recordError("infrastructure");
			return new IndexedResult(index, item(index, operation, "infrastructure_error",
					"Permission change failed because of an infrastructure error."));
		} finally {
			MDC.remove("correlationId");
		}
	}

	private BulkPermissionResult.ItemResult item(int index, PermissionOperation operation, String status, String message) {
		return new BulkPermissionResult.ItemResult(index, operation.action().name(),
				operation.request().username(), status, message);
	}

	private int count(List<BulkPermissionResult.ItemResult> results, String status) {
		return (int) results.stream().filter(result -> result != null && status.equals(result.status())).count();
	}

	private void meterRegistryRejected() {
		rejectedTasks.increment();
		permissionService.recordError("infrastructure");
	}

	private record IndexedResult(int index, BulkPermissionResult.ItemResult result) {
	}

	@jakarta.annotation.PreDestroy
	public void shutdown() {
		executor.shutdown();
		try {
			if (!executor.awaitTermination(15, TimeUnit.SECONDS)) {
				executor.shutdownNow();
				executor.awaitTermination(5, TimeUnit.SECONDS);
			}
		} catch (InterruptedException exception) {
			executor.shutdownNow();
			Thread.currentThread().interrupt();
		}
	}
}
