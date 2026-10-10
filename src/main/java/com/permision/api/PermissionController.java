package com.permision.api;

import com.permision.service.BulkPermissionService;
import com.permision.service.PermissionService;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/permissions")
public class PermissionController {
	private final PermissionService permissionService;
	private final BulkPermissionService bulkPermissionService;

	public PermissionController(PermissionService permissionService, BulkPermissionService bulkPermissionService) {
		this.permissionService = permissionService;
		this.bulkPermissionService = bulkPermissionService;
	}

	@PostMapping("/grant")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(summary = "Grant table permissions", description = "Applies the requested allow-listed privileges to a MariaDB account.")
	@ApiResponse(responseCode = "204", description = "Privileges granted")
	@ApiResponse(responseCode = "400", description = "Invalid account, table, or permission")
	@ApiResponse(responseCode = "502", description = "MariaDB rejected the operation")
	public void grant(@Valid @RequestBody PermissionRequest request) {
		permissionService.apply(request, "GRANT");
	}

	@PostMapping("/revoke")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(summary = "Revoke table permissions", description = "Removes the requested allow-listed privileges from a MariaDB account.")
	@ApiResponse(responseCode = "204", description = "Privileges revoked")
	@ApiResponse(responseCode = "400", description = "Invalid account, table, or permission")
	@ApiResponse(responseCode = "502", description = "MariaDB rejected the operation")
	public void revoke(@Valid @RequestBody PermissionRequest request) {
		permissionService.apply(request, "REVOKE");
	}

	@GetMapping("/{username}")
	@Operation(summary = "List table privileges", description = "Returns the account's table privileges from MariaDB information_schema.")
	@ApiResponse(responseCode = "200", description = "Privileges returned")
	@ApiResponse(responseCode = "400", description = "Invalid account name")
	public List<PrivilegeResult> privileges(@PathVariable String username) {
		return permissionService.privilegesFor(username);
	}

	@PostMapping("/bulk")
	@Operation(summary = "Apply a batch of permission operations", description = "Runs grant/revoke operations concurrently and returns an independent result for each item.")
	@ApiResponse(responseCode = "200", description = "Per-operation results, including partial failures")
	@ApiResponse(responseCode = "400", description = "Invalid request or batch exceeds its configured maximum")
	public BulkPermissionResult bulk(@Valid @RequestBody BulkPermissionRequest request) {
		return bulkPermissionService.apply(request);
	}
}
