package com.permision.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(info = @Info(
		title = "MariaDB Permission Management API",
		version = "v1",
		description = "Manage allow-listed table privileges in MariaDB. The API must be deployed behind an authenticated gateway."))
public class OpenApiConfiguration {
}
