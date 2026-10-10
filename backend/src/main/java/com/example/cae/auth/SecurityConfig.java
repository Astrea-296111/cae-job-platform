package com.example.cae.auth;

import com.example.cae.common.ApiErrors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {
  @Bean
  SecurityFilterChain security(
      HttpSecurity http,
      ApiErrors errors,
      @Value("${SERVICE_TOKEN:demo-service-token}") String serviceToken)
      throws Exception {
    return http.csrf(csrf -> csrf.disable())
        .addFilterBefore(
            new ServiceTokenFilter(serviceToken, errors),
            UsernamePasswordAuthenticationFilter.class)
        .authorizeHttpRequests(
            a ->
                a.requestMatchers("/actuator/health", "/actuator/prometheus", "/internal/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .httpBasic(
            basic ->
                basic.authenticationEntryPoint(
                    (request, response, error) ->
                        errors.write(
                            request,
                            response,
                            401,
                            "UNAUTHENTICATED",
                            "Authentication is required")))
        .exceptionHandling(
            exceptions ->
                exceptions.accessDeniedHandler(
                    (request, response, error) ->
                        errors.write(request, response, 403, "FORBIDDEN", "Access is denied")))
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
