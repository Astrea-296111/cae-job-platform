ALTER TABLE cae_job
  ADD active_pipeline_parse_id BIGINT,
  ADD active_post_version INT NOT NULL DEFAULT 1,
  ADD active_report_version INT NOT NULL DEFAULT 1,
  ADD pipeline_generation BIGINT NOT NULL DEFAULT 0,
  ADD CONSTRAINT fk_job_active_parse FOREIGN KEY(active_pipeline_parse_id) REFERENCES cae_result_parse_record(id) ON DELETE SET NULL;
UPDATE cae_job j JOIN (
  SELECT p.job_id,MIN(p.id) AS parse_id FROM cae_result_parse_record p
  JOIN cae_job original ON original.id=p.job_id AND original.current_execution_id=p.execution_id
  WHERE p.status='COMPLETE' GROUP BY p.job_id
) source ON source.job_id=j.id
SET j.active_pipeline_parse_id=source.parse_id,j.pipeline_generation=1;

CREATE TABLE cae_pipeline_stage (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  job_id BIGINT NOT NULL,
  execution_id BIGINT NOT NULL,
  parse_record_id BIGINT NOT NULL,
  parser_version VARCHAR(32) NOT NULL,
  kind VARCHAR(16) NOT NULL,
  stage_version INT NOT NULL,
  post_version INT NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'REQUESTED',
  request_json JSON NOT NULL,
  request_fingerprint VARCHAR(64) NOT NULL,
  pipeline_generation BIGINT NOT NULL,
  claim_owner VARCHAR(64),
  claim_generation BIGINT NOT NULL DEFAULT 0,
  claim_until DATETIME(6),
  failure_message VARCHAR(1000),
  next_requested_at DATETIME(6),
  manifest_artifact_id BIGINT,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  completed_at DATETIME(6),
  UNIQUE KEY uk_pipeline_version(parse_record_id,kind,stage_version),
  KEY idx_pipeline_job(job_id),
  FOREIGN KEY(job_id) REFERENCES cae_job(id),
  FOREIGN KEY(execution_id) REFERENCES cae_job_execution(id),
  FOREIGN KEY(parse_record_id) REFERENCES cae_result_parse_record(id),
  FOREIGN KEY(manifest_artifact_id) REFERENCES cae_artifact(id),
  CHECK(kind IN ('POST','REPORT')),
  CHECK(stage_version BETWEEN 1 AND 10000)
);
CREATE TABLE cae_report (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  job_id BIGINT NOT NULL,
  execution_id BIGINT NOT NULL,
  parse_record_id BIGINT NOT NULL,
  parser_version VARCHAR(32) NOT NULL,
  report_version INT NOT NULL,
  post_version INT NOT NULL,
  stage_id BIGINT NOT NULL,
  artifact_id BIGINT NOT NULL,
  report_status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
  template_code VARCHAR(64) NOT NULL,
  template_version VARCHAR(32) NOT NULL,
  baseline_job_no VARCHAR(64),
  context_json JSON NOT NULL,
  reviewer VARCHAR(100),
  reviewed_at DATETIME(6),
  approver VARCHAR(100),
  approved_at DATETIME(6),
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_report_parse_version(parse_record_id,report_version),
  FOREIGN KEY(job_id) REFERENCES cae_job(id),
  FOREIGN KEY(execution_id) REFERENCES cae_job_execution(id),
  FOREIGN KEY(parse_record_id) REFERENCES cae_result_parse_record(id),
  FOREIGN KEY(stage_id) REFERENCES cae_pipeline_stage(id),
  FOREIGN KEY(artifact_id) REFERENCES cae_artifact(id),
  CHECK(report_status IN ('DRAFT','REVIEWED','APPROVED'))
);
CREATE TABLE cae_report_generation_request (
  job_id BIGINT NOT NULL,
  idempotency_key VARCHAR(100) NOT NULL,
  request_fingerprint VARCHAR(64) NOT NULL,
  parse_record_id BIGINT NOT NULL,
  stage_version INT NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY(job_id,idempotency_key),
  FOREIGN KEY(job_id) REFERENCES cae_job(id),
  FOREIGN KEY(parse_record_id) REFERENCES cae_result_parse_record(id)
);
