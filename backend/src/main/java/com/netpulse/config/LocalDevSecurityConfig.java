package com.netpulse.config;

import java.io.IOException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Step 4: API is intentionally unauthenticated and bound to localhost.
 * This filter adds a response header so clients can see that clearly.
 */
@Configuration
public class LocalDevSecurityConfig {

    @Component
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public static class UnauthenticatedModeHeaderFilter extends OncePerRequestFilter {

        @Value("${netpulse.security.mode:UNAUTHENTICATED_LOCALHOST_ONLY}")
        private String securityMode;

        @Override
        protected void doFilterInternal(HttpServletRequest request,
                                        HttpServletResponse response,
                                        FilterChain filterChain)
                throws ServletException, IOException {
            response.setHeader("X-NetPulse-Security-Mode", securityMode);
            filterChain.doFilter(request, response);
        }
    }
}
