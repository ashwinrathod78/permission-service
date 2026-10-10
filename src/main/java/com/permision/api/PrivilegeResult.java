package com.permision.api;

public record PrivilegeResult(String username, String database, String table, String privilege, boolean grantable) {
}
