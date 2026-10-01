package com.dragosfotache.gtbackend.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Covers successful, validation-error, and framework-generated auth responses.
 */
@Component
public class AuthNoStoreFilter extends OncePerRequestFilter {
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        return !(uri.startsWith(context + "/v1/auth/") || uri.startsWith(context + "/v1/account/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        try {
            chain.doFilter(request, response);
        } finally {
            response.setHeader("Cache-Control", "no-store");
        }
    }
}
