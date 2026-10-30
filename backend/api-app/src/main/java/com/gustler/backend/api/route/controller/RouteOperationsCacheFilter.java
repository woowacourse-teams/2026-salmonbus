package com.gustler.backend.api.route.controller;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@ConditionalOnProperty(prefix = "route-operations", name = "enabled", havingValue = "true")
public class RouteOperationsCacheFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        if (!request.getRequestURI().startsWith("/api/v1/routes")) {
            chain.doFilter(request, response);
            return;
        }
        response.setHeader("Cache-Control", "no-store");
        chain.doFilter(
            request,
            new HttpServletResponseWrapper(response) {
                @Override
                public void setHeader(String name, String value) {
                    if (name.equalsIgnoreCase("Cache-Control")) {
                        super.setHeader(name, "no-store");
                        return;
                    }
                    super.setHeader(name, value);
                }

                @Override
                public void addHeader(String name, String value) {
                    setHeader(name, value);
                }
            });
    }
}
