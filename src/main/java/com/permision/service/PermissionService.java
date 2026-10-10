package com.permision.service;

import com.permision.api.PermissionRequest;
import com.permision.api.PrivilegeResult;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class PermissionService {
	private static final Logger log = LoggerFactory.getLogger(PermissionService.class);
	private static final String[] ALLOWED_PERMISSIONS = {
			"SELECT", "INSERT", "UPDATE", "DELETE", "CREATE", "DROP", "ALTER", "INDEX", "REFERENCES"
	};
	private static final int LOCK_STRIPES = 256;

	private final JdbcTemplate jdbcTemplate;
	private final MeterRegistry meterRegistry;
	private final ReentrantLock[] principalLocks = new ReentrantLock[LOCK_STRIPES];

	public PermissionService(JdbcTemplate jdbcTemplate, MeterRegistry meterRegistry) {
		this.jdbcTemplate = jdbcTemplate;
		this.meterRegistry = meterRegistry;
		Arrays.setAll(principalLocks, ignored -> new ReentrantLock());
	}

	public void apply(PermissionRequest request, String action) {
		validateUsername(request.username());
		String normalizedAction = action == null ? "" : action.toUpperCase(Locale.ROOT);
		if (!"GRANT".equals(normalizedAction) && !"REVOKE".equals(normalizedAction)) {
			recordError("validation");
			throw new IllegalArgumentException("Action must be GRANT or REVOKE.");
		}
		List<String> permissions = normalizePermissions(request.permissions());
		ReentrantLock lock = lockFor(request.username());
		try {
			lock.lockInterruptibly();
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new OperationCancelledException("Operation was cancelled while waiting for the principal lock.");
		}

		try {
			validateTargetExists(request.database(), request.table());
			String sql = normalizedAction + " " + String.join(", ", permissions) + " ON "
					+ quoteIdentifier(request.database()) + "." + quoteIdentifier(request.table())
					+ ("GRANT".equals(normalizedAction) ? " TO " : " FROM ")
					+ quoteAccount(request.username());
			log.info("Permission SQL prepared action={} sql={}", normalizedAction.toLowerCase(Locale.ROOT), sql);
			Timer.Sample sample = Timer.start(meterRegistry);
			try {
				jdbcTemplate.execute(sql);
				sample.stop(Timer.builder("permission.database.roundtrip")
						.description("Permission-management SQL round-trip time")
						.tag("action", normalizedAction.toLowerCase(Locale.ROOT))
						.serviceLevelObjectives(java.time.Duration.ofMillis(500))
						.register(meterRegistry));
				recordOutcome(normalizedAction, "success");
			} catch (DataAccessException exception) {
				sample.stop(Timer.builder("permission.database.roundtrip")
						.tag("action", normalizedAction.toLowerCase(Locale.ROOT))
						.serviceLevelObjectives(java.time.Duration.ofMillis(500))
						.register(meterRegistry));
				recordOutcome(normalizedAction, "sql_error");
				throw exception;
			}
		} finally {
			lock.unlock();
		}
	}

	public List<PrivilegeResult> privilegesFor(String username) {
		validateUsername(username);
		String grantee = quoteAccount(username);
		return jdbcTemplate.query(
				"SELECT TABLE_SCHEMA, TABLE_NAME, PRIVILEGE_TYPE, IS_GRANTABLE "
						+ "FROM permission_metadata.permission_table_privileges WHERE GRANTEE = ? "
						+ "ORDER BY TABLE_SCHEMA, TABLE_NAME, PRIVILEGE_TYPE",
				(resultSet, rowNum) -> new PrivilegeResult(username, resultSet.getString("TABLE_SCHEMA"),
						resultSet.getString("TABLE_NAME"), resultSet.getString("PRIVILEGE_TYPE"),
						"YES".equalsIgnoreCase(resultSet.getString("IS_GRANTABLE"))),
				grantee);
	}

	public void validateUsername(String username) {
		if (username == null || !username.matches("[A-Za-z_][A-Za-z0-9_.-]{0,79}")) {
			recordError("validation");
			throw new IllegalArgumentException("Username must be a valid MariaDB account name.");
		}
	}

	public void recordError(String category) {
		meterRegistry.counter("permission.errors", "class", category).increment();
	}

	private void validateTargetExists(String database, String table) {
		if (database == null || !database.matches("[A-Za-z_][A-Za-z0-9_$]{0,63}")
				|| table == null || !table.matches("[A-Za-z_][A-Za-z0-9_$]{0,63}")) {
			recordError("validation");
			throw new IllegalArgumentException("Database and table must be valid MariaDB identifiers.");
		}
		Integer count = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?",
				Integer.class, database, table);
		if (count == null || count != 1) {
			recordError("validation");
			throw new IllegalArgumentException("The requested database table does not exist.");
		}
	}

	private List<String> normalizePermissions(List<String> permissions) {
		if (permissions == null || permissions.isEmpty()) {
			recordError("validation");
			throw new IllegalArgumentException("At least one permission is required.");
		}
		List<String> normalized = permissions.stream()
				.map(permission -> permission == null ? "" : permission.trim().toUpperCase(Locale.ROOT))
				.distinct()
				.toList();
		for (String permission : normalized) {
			if (Arrays.stream(ALLOWED_PERMISSIONS).noneMatch(permission::equals)) {
				recordError("validation");
				throw new IllegalArgumentException("One or more permissions are not allowed.");
			}
		}
		return normalized;
	}

	private ReentrantLock lockFor(String username) {
		return principalLocks[Math.floorMod(username.toLowerCase(Locale.ROOT).hashCode(), LOCK_STRIPES)];
	}

	private void recordOutcome(String action, String outcome) {
		meterRegistry.counter("permission.operation", "action", action.toLowerCase(Locale.ROOT),
				"outcome", outcome).increment();
	}

	private String quoteIdentifier(String identifier) {
		return "`" + identifier + "`";
	}

	private String quoteAccount(String username) {
		return "'" + username + "'@'%'";
	}
}
