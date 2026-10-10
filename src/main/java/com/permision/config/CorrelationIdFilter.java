package com.permision.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {
	private static final String HEADER = "X-Correlation-ID";

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String requested = request.getHeader(HEADER);
		String correlationId = requested != null && requested.matches("[A-Za-z0-9._-]{1,64}")
				? requested : UUID.randomUUID().toString();
		MDC.put("correlationId", correlationId);
		response.setHeader(HEADER, correlationId);
		try {
			chain.doFilter(request, response);
		} finally {
			MDC.remove("correlationId");
		}
	}
}
