package com.example.cae.auth;

import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {
  @GetMapping("/api/v1/auth/me")
  public Map<String, Object> me(Authentication user) {
    return Map.of(
        "username",
        user.getName(),
        "roles",
        user.getAuthorities().stream().map(a -> a.getAuthority().replace("ROLE_", "")).toList());
  }
}
