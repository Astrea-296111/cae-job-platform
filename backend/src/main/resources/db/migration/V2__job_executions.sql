CREATE TABLE cae_job_execution (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  job_id BIGINT NOT NULL,
  attempt_no INT NOT NULL,
  submit_request_id VARCHAR(100) NOT NULL,
  external_execution_id VARCHAR(128) NULL,
  node_id VARCHAR(64) NOT NULL,
  reservation_id BIGINT NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'SUBMITTING',
  workspace VARCHAR(1024) NOT NULL,
  request_json JSON NOT NULL,
  process_identity VARCHAR(128) NULL,
  pid BIGINT NULL,
  exit_code INT NULL,
  raw_solver_code VARCHAR(128) NULL,
  raw_message VARCHAR(2000) NULL,
  progress DECIMAL(5,2) NOT NULL DEFAULT 0,
  cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
  version BIGINT NOT NULL DEFAULT 0,
  submitted_at DATETIME(6) NULL,
  started_at DATETIME(6) NULL,
  finished_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  active_job_id BIGINT GENERATED ALWAYS AS
    (CASE WHEN status IN ('SUBMITTING','SUBMITTED','RUNNING','UNKNOWN') THEN job_id ELSE NULL END) STORED,
  CONSTRAINT fk_execution_job FOREIGN KEY (job_id) REFERENCES cae_job(id),
  UNIQUE KEY uk_execution_attempt (job_id,attempt_no),
  UNIQUE KEY uk_execution_request (submit_request_id),
  UNIQUE KEY uk_execution_external (external_execution_id),
  UNIQUE KEY uk_execution_active_job (active_job_id),
  KEY idx_execution_status_updated (status,updated_at),
  KEY idx_execution_node_status (node_id,status)
);

ALTER TABLE cae_job ADD CONSTRAINT fk_job_current_execution
  FOREIGN KEY (current_execution_id) REFERENCES cae_job_execution(id);

CREATE TABLE cae_job_submission_request (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  job_id BIGINT NOT NULL,
  idempotency_key VARCHAR(100) NOT NULL,
  operation VARCHAR(16) NOT NULL,
  fingerprint CHAR(64) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_submission_key (job_id,idempotency_key),
  CONSTRAINT fk_submission_job FOREIGN KEY (job_id) REFERENCES cae_job(id)
);
