package com.permision.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PoolSizingValidator {
	public PoolSizingValidator(@Value("${permission.bulk.threads:4}") int bulkThreads,
			@Value("${spring.datasource.hikari.maximum-pool-size:5}") int connectionPoolSize) {
		if (connectionPoolSize < bulkThreads + 1) {
			throw new IllegalArgumentException("JDBC pool must reserve one connection beyond bulk worker count.");
		}
	}
}
