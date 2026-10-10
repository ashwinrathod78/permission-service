package com.permision.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record PermissionOperation(@NotNull Action action, @NotNull @Valid PermissionRequest request) {
	public enum Action {
		GRANT, REVOKE
	}
}
