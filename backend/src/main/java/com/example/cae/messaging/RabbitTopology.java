package com.example.cae.messaging;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitTopology {
  public static final String COMMAND_EXCHANGE = "cae.command";
  public static final String EVENT_EXCHANGE = "cae.event";
  public static final String DEAD_EXCHANGE = "cae.dead";
  public static final String SUBMIT_QUEUE = "cae.job.submit";
  public static final String EVENT_QUEUE = "cae.event.audit";
  private static final Map<String, String> COMMAND_ROUTES =
      Map.of(
          "JOB_SUBMIT_REQUESTED",
          "job.submit",
          "EXECUTION_SUBMIT_REQUESTED",
          "job.submit",
          "RESULT_PARSE_REQUESTED",
          "job.parse",
          "POST_PROCESS_REQUESTED",
          "job.postprocess",
          "REPORT_REQUESTED",
          "job.report");

  public static String route(String eventType) {
    String route = COMMAND_ROUTES.get(eventType);
    if (route == null)
      throw new IllegalArgumentException("Unsupported command event: " + eventType);
    return route;
  }

  public static boolean isCommand(String eventType) {
    return COMMAND_ROUTES.containsKey(eventType);
  }

  @Bean
  Declarables caeTopology() {
    DirectExchange commands = new DirectExchange(COMMAND_EXCHANGE, true, false);
    DirectExchange dead = new DirectExchange(DEAD_EXCHANGE, true, false);
    TopicExchange events = new TopicExchange(EVENT_EXCHANGE, true, false);
    List<Declarable> definitions = new ArrayList<>(List.of(commands, dead, events));
    for (String stage : List.of("submit", "parse", "postprocess", "report")) {
      String routing = "job." + stage;
      Queue queue =
          QueueBuilder.durable("cae.job." + stage)
              .deadLetterExchange(DEAD_EXCHANGE)
              .deadLetterRoutingKey(routing + ".dead")
              .build();
      Queue dlq = QueueBuilder.durable(queue.getName() + ".dlq").build();
      definitions.addAll(
          List.of(
              queue,
              dlq,
              BindingBuilder.bind(queue).to(commands).with(routing),
              BindingBuilder.bind(dlq).to(dead).with(routing + ".dead")));
    }
    // Every event has a durable route even before the SSE observer is installed.
    Queue audit = QueueBuilder.durable(EVENT_QUEUE).build();
    definitions.addAll(List.of(audit, BindingBuilder.bind(audit).to(events).with("#")));
    return new Declarables(definitions);
  }
}
