FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
COPY src src
RUN --mount=type=cache,target=/root/.m2 \
    --mount=type=secret,id=build_proxy \
    --mount=type=secret,id=maven_settings \
    --mount=type=secret,id=java_truststore \
    set -eu; \
    if [ -s /run/secrets/build_proxy ]; then \
      export HTTPS_PROXY="$(cat /run/secrets/build_proxy)" HTTP_PROXY="$(cat /run/secrets/build_proxy)"; \
    fi; \
    set -- -B; \
    if [ -s /run/secrets/maven_settings ]; then set -- "$@" -s /run/secrets/maven_settings; fi; \
    if [ -s /run/secrets/java_truststore ]; then \
      export MAVEN_OPTS="${MAVEN_OPTS:-} -Djavax.net.ssl.trustStore=/run/secrets/java_truststore"; \
    fi; \
    mvn "$@" -DskipTests package
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --from=build /build/target/cae-platform-0.1.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
