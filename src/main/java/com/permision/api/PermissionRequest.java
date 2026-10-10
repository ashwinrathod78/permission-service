package com.permision.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record PermissionRequest(
		@NotBlank @Pattern(regexp = "[A-Za-z_][A-Za-z0-9_.-]{0,79}") String username,
		@NotBlank @Pattern(regexp = "[A-Za-z_][A-Za-z0-9_$]{0,63}") String database,
		@NotBlank @Pattern(regexp = "[A-Za-z_][A-Za-z0-9_$]{0,63}") String table,
		@NotEmpty @Size(max = 32) List<@NotBlank String> permissions) {
}
