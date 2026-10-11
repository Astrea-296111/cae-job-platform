ALTER TABLE cae_job_execution
  ADD COLUMN terminal_applied_at DATETIME(6) NULL,
  ADD COLUMN result_parse_requested_at DATETIME(6) NULL;

CREATE TABLE cae_execution_poll_lease (
  execution_id BIGINT PRIMARY KEY,
  job_id BIGINT NOT NULL,
  owner_token VARCHAR(64) NULL,
  generation BIGINT NOT NULL DEFAULT 0,
  lease_until DATETIME(6) NULL,
  next_poll_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  failure_count INT NOT NULL DEFAULT 0,
  last_observed_at DATETIME(6) NULL,
  last_error VARCHAR(1000) NULL,
  CONSTRAINT fk_poll_execution FOREIGN KEY (execution_id) REFERENCES cae_job_execution(id),
  KEY idx_poll_due(next_poll_at,lease_until),
  CHECK(generation >= 0 AND failure_count >= 0)
);
