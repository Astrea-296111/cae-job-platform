package com.example.cae.events;

import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class EventStreamController {
  private final EventStreamService streams;

  public EventStreamController(EventStreamService streams) {
    this.streams = streams;
  }

  @GetMapping(value = "/api/v1/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream(
      Authentication user,
      @RequestParam(required = false) String jobNo,
      @RequestParam(required = false) Long projectId,
      @RequestHeader(value = "Last-Event-ID", required = false) String cursor,
      jakarta.servlet.http.HttpServletResponse response)
      throws IOException {
    response.setHeader("X-Accel-Buffering", "no");
    response.setHeader("Cache-Control", "no-cache");
    return streams.connect(user, jobNo, projectId, cursor);
  }
}
