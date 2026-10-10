package com.permision.api;

import java.util.List;

public record PermissionResult(String username, String database, String table, List<String> permissions,
		String action, String status) {
}
