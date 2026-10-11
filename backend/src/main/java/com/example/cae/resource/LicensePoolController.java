package com.example.cae.resource;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/license-pools")
public class LicensePoolController {
  private final ResourceReservationService resources;

  public LicensePoolController(ResourceReservationService resources) {
    this.resources = resources;
  }

  @GetMapping
  public List<Map<String, Object>> list() {
    return resources.licenses();
  }
}
