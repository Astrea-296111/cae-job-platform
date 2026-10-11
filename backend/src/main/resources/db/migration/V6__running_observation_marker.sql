ALTER TABLE cae_job_execution
  ADD COLUMN running_observed_at DATETIME(6) NULL;

-- UNKNOWN preserves a previously confirmed start. PID alone only proves Popen,
-- so it must not be used as evidence of a RUNNING observation.
UPDATE cae_job_execution
SET running_observed_at=COALESCE(started_at,updated_at)
WHERE status='RUNNING' OR (status='UNKNOWN' AND started_at IS NOT NULL);
