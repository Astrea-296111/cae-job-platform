package com.example.cae.auth;

import com.example.cae.common.ApiErrors;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.web.filter.OncePerRequestFilter;

/** Internal Agent calls require a service token independently of user Basic authentication. */
public final class ServiceTokenFilter extends OncePerRequestFilter {
  private final byte[] token;
  private final ApiErrors errors;

  public ServiceTokenFilter(String token, ApiErrors errors) {
    if (token == null || token.isBlank())
      throw new IllegalArgumentException("Service token is required");
    this.token = token.getBytes(StandardCharsets.UTF_8);
    this.errors = errors;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    return !(path.equals("/internal") || path.startsWith("/internal/"));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String supplied = request.getHeader("X-Service-Token");
    if (supplied == null
        || !MessageDigest.isEqual(token, supplied.getBytes(StandardCharsets.UTF_8))) {
      errors.write(request, response, 401, "UNAUTHENTICATED", "A valid service token is required");
      return;
    }
    chain.doFilter(request, response);
  }
}
