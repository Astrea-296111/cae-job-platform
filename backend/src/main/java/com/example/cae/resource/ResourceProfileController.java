package com.example.cae.resource;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/resource-profiles")
public class ResourceProfileController {
  private final ResourceProfileRegistry profiles;

  public ResourceProfileController(ResourceProfileRegistry profiles) {
    this.profiles = profiles;
  }

  @GetMapping
  public List<Map<String, Object>> list() {
    return profiles.list();
  }

  @GetMapping("/{name}")
  public Map<String, Object> find(@PathVariable String name) {
    return profiles.find(name);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Object> create(
      Authentication user, @Valid @RequestBody ResourceProfileRegistry.Definition value) {
    return profiles.create(user, value);
  }

  @PutMapping("/{name}")
  public Map<String, Object> update(
      Authentication user,
      @PathVariable String name,
      @Valid @RequestBody ResourceProfileRegistry.Definition value) {
    return profiles.update(user, name, value);
  }

  @DeleteMapping("/{name}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(Authentication user, @PathVariable String name, @RequestParam long version) {
    profiles.delete(user, name, version);
  }
}
