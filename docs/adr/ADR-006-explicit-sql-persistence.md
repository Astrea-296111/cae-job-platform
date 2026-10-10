# ADR-006: Explicit SQL for transactional invariants

Status: accepted.

Use Spring JdbcTemplate repositories instead of an ORM for the public reproduction. Parameterized SQL makes status/version CAS, conditional resource updates, uniqueness, and MySQL `FOR UPDATE SKIP LOCKED` visible to readers and integration tests. Every application transaction still uses Spring transaction management.

This is a documented change from the suggested MyBatis-Plus stack. It removes duplicate persistence abstractions without weakening MySQL persistence or the required invariants. Repository methods isolate SQL from API controllers. Zero updated rows in a transition are a conflict, never permission to overwrite a newer state.
