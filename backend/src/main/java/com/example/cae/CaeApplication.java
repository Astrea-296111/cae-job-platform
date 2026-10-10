package com.example.cae;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CaeApplication {
  public static void main(String[] args) {
    SpringApplication.run(CaeApplication.class, args);
  }
}
