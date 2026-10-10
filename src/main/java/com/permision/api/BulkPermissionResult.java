package com.permision.api;

import java.util.List;

public record BulkPermissionResult(List<ItemResult> results, int succeeded, int failed, int timedOut, int cancelled) {
	public record ItemResult(int index, String action, String username, String status, String message) {
	}
}
