CREATE TABLE cae_job_create_request (
  actor VARCHAR(64) NOT NULL,
  idempotency_key VARCHAR(100) NOT NULL,
  request_fingerprint VARCHAR(64) NOT NULL,
  job_id BIGINT,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY(actor,idempotency_key),
  FOREIGN KEY(job_id) REFERENCES cae_job(id)
);
ALTER TABLE cae_job ADD queued_at DATETIME(6);
UPDATE cae_job j SET queued_at=(SELECT MAX(e.created_at) FROM cae_job_event e WHERE e.job_id=j.id AND e.event_type='JOB_SUBMIT_REQUESTED' AND e.created_at<=COALESCE((SELECT x.started_at FROM cae_job_execution x WHERE x.id=j.current_execution_id),UTC_TIMESTAMP(6)));
CREATE TABLE cae_report_template (
  code VARCHAR(64) NOT NULL,
  version VARCHAR(32) NOT NULL,
  analysis_group VARCHAR(32) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  PRIMARY KEY(code,version)
);
INSERT INTO cae_report_template(code,version,analysis_group) VALUES
 ('STRUCTURE_DRAFT','v1','STRUCTURE'),('PILOT_DRAFT','v1','PILOT');
