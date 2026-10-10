package com.example.cae.common;

import java.util.Map;
import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
  private final HttpStatus status;
  private final String code;
  private final Map<String, Object> details;

  public ApiException(HttpStatus status, String code, String message) {
    this(status, code, message, Map.of());
  }

  public ApiException(HttpStatus status, String code, String message, Map<String, Object> details) {
    super(message);
    this.status = status;
    this.code = code;
    this.details = details;
  }

  public HttpStatus status() {
    return status;
  }

  public String code() {
    return code;
  }

  public Map<String, Object> details() {
    return details;
  }

  public static ApiException notFound(String entity) {
    return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", entity + " was not found");
  }

  public static ApiException conflict(String message) {
    return new ApiException(HttpStatus.CONFLICT, "JOB_STATE_CONFLICT", message);
  }

  public static ApiException invalid(String message) {
    return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", message);
  }

  public static ApiException forbidden() {
    return new ApiException(
        HttpStatus.FORBIDDEN, "FORBIDDEN", "Access to this project or operation is denied");
  }
}
