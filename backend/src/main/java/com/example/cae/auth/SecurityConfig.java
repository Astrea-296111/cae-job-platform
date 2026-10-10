package com.example.cae.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
  @Bean
  SecurityFilterChain security(HttpSecurity http) throws Exception {
    return http.csrf(csrf -> csrf.disable())
        .authorizeHttpRequests(
            a ->
                a.requestMatchers("/actuator/health", "/actuator/prometheus", "/internal/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .httpBasic(Customizer.withDefaults())
        .build();
  }

  @Bean
  UserDetailsService users() {
    return new InMemoryUserDetailsManager(
        User.withUsername("admin")
            .password("{noop}admin123")
            .roles("ADMIN", "PROJECT_LEAD", "CAE_ENGINEER")
            .build(),
        User.withUsername("lead")
            .password("{noop}lead123")
            .roles("PROJECT_LEAD", "CAE_ENGINEER")
            .build(),
        User.withUsername("engineer").password("{noop}engineer123").roles("CAE_ENGINEER").build());
  }
}
