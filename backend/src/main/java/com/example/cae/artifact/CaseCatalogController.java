package com.example.cae.artifact;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CaseCatalogController {
  private final CaseCatalogService catalog;

  public CaseCatalogController(CaseCatalogService catalog) {
    this.catalog = catalog;
  }

  @GetMapping("/api/v1/cases")
  public List<Map<String, Object>> list() {
    return catalog.list();
  }
}
