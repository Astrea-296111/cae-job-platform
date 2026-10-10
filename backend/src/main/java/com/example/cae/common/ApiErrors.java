package com.example.cae.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiErrors {
  private static final Logger LOG = LoggerFactory.getLogger(ApiErrors.class);
  private final JsonCodec json;

  public ApiErrors(JsonCodec json) {
    this.json = json;
  }

  public Map<String, Object> body(
      String code, String message, HttpServletRequest request, Map<String, Object> details) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("success", false);
    result.put("code", code);
    result.put("message", message);
    result.put("traceId", request.getAttribute(TraceFilter.ATTRIBUTE));
    result.put("details", details);
    return result;
  }

  public void write(
      HttpServletRequest request,
      HttpServletResponse response,
      int status,
      String code,
      String message)
      throws IOException {
    response.setStatus(status);
    response.setContentType("application/json;charset=UTF-8");
    response.getWriter().write(json.write(body(code, message, request, Map.of())));
  }

  @ExceptionHandler(ApiException.class)
  ResponseEntity<Map<String, Object>> domain(ApiException error, HttpServletRequest request) {
    return ResponseEntity.status(error.status())
        .body(body(error.code(), error.getMessage(), request, error.details()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<Map<String, Object>> validation(
      MethodArgumentNotValidException error, HttpServletRequest request) {
    Map<String, Object> fields = new LinkedHashMap<>();
    error
        .getBindingResult()
        .getFieldErrors()
        .forEach(e -> fields.put(e.getField(), e.getDefaultMessage()));
    return ResponseEntity.badRequest()
        .body(body("INVALID_INPUT", "Request fields are invalid", request, fields));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<Map<String, Object>> malformed(HttpServletRequest request) {
    return ResponseEntity.badRequest()
        .body(
            body(
                "INVALID_INPUT",
                "Request JSON is malformed or contains an unsupported value",
                request,
                Map.of()));
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ResponseEntity<Map<String, Object>> queryType(HttpServletRequest request) {
    return ResponseEntity.badRequest()
        .body(
            body(
                "INVALID_INPUT",
                "Query or path parameter has an unsupported type",
                request,
                Map.of()));
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<Map<String, Object>> constraint(HttpServletRequest request) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            body(
                "DATA_CONFLICT",
                "A unique value or referenced record conflicts",
                request,
                Map.of()));
  }

  @ExceptionHandler(AccessDeniedException.class)
  ResponseEntity<Map<String, Object>> denied(HttpServletRequest request) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .body(body("FORBIDDEN", "Access is denied", request, Map.of()));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Map<String, Object>> unexpected(Exception error, HttpServletRequest request) {
    LOG.error(
        "Unexpected API error traceId={}", request.getAttribute(TraceFilter.ATTRIBUTE), error);
    return ResponseEntity.internalServerError()
        .body(body("INTERNAL_ERROR", "Unexpected server error", request, Map.of()));
  }
}
