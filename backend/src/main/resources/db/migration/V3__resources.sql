CREATE TABLE cae_compute_node (
  node_id VARCHAR(64) PRIMARY KEY,
  hostname VARCHAR(128) NOT NULL,
  agent_url VARCHAR(255) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'OFFLINE',
  drained BOOLEAN NOT NULL DEFAULT FALSE,
  workspace_reachable BOOLEAN NOT NULL DEFAULT FALSE,
  cpu_total INT NOT NULL,
  cpu_used INT NOT NULL DEFAULT 0,
  cpu_reserved INT NOT NULL DEFAULT 0,
  observed_cpu_used INT NOT NULL DEFAULT 0,
  memory_total_gb INT NOT NULL,
  memory_used_gb INT NOT NULL DEFAULT 0,
  memory_reserved_gb INT NOT NULL DEFAULT 0,
  observed_memory_used_gb INT NOT NULL DEFAULT 0,
  solver_tags JSON NOT NULL,
  running_jobs JSON NOT NULL,
  last_heartbeat DATETIME(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CHECK(cpu_used >= 0 AND cpu_reserved >= 0),
  CHECK(memory_used_gb >= 0 AND memory_reserved_gb >= 0)
);
CREATE TABLE cae_license_pool (
  feature VARCHAR(32) PRIMARY KEY,
  total_tokens INT NOT NULL,
  reserved_tokens INT NOT NULL DEFAULT 0,
  in_use_tokens INT NOT NULL DEFAULT 0,
  CHECK(reserved_tokens >= 0 AND in_use_tokens >= 0 AND reserved_tokens + in_use_tokens <= total_tokens)
);
CREATE TABLE cae_resource_reservation (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  job_id BIGINT NOT NULL,
  execution_id BIGINT,
  node_id VARCHAR(64) NOT NULL,
  feature VARCHAR(32) NOT NULL,
  token_count INT NOT NULL,
  cpu INT NOT NULL,
  memory_gb INT NOT NULL,
  status VARCHAR(16) NOT NULL,
  active_job_id BIGINT GENERATED ALWAYS AS (CASE WHEN status IN ('RESERVED','IN_USE') THEN job_id ELSE NULL END) STORED,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  accepted_at DATETIME(6),
  released_at DATETIME(6),
  UNIQUE KEY uk_active_job_reservation(active_job_id),
  UNIQUE KEY uk_execution_reservation(execution_id),
  KEY idx_node_reservation(node_id,status),
  FOREIGN KEY(job_id) REFERENCES cae_job(id),
  FOREIGN KEY(execution_id) REFERENCES cae_job_execution(id),
  FOREIGN KEY(node_id) REFERENCES cae_compute_node(node_id),
  FOREIGN KEY(feature) REFERENCES cae_license_pool(feature),
  CHECK(cpu > 0 AND memory_gb > 0 AND token_count > 0),
  CHECK(status IN ('RESERVED','IN_USE','RELEASED'))
);
CREATE TABLE cae_resource_profile (
  name VARCHAR(32) PRIMARY KEY,
  cpu INT NOT NULL,
  memory_gb INT NOT NULL,
  walltime_min INT NOT NULL,
  license_tokens INT NOT NULL DEFAULT 1,
  allowed_analysis_types JSON NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  version BIGINT NOT NULL DEFAULT 0,
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CHECK(cpu BETWEEN 1 AND 32 AND memory_gb BETWEEN 1 AND 64),
  CHECK(walltime_min BETWEEN 1 AND 120 AND license_tokens BETWEEN 1 AND 16)
);
ALTER TABLE cae_job ADD COLUMN license_feature VARCHAR(32) NOT NULL DEFAULT 'OPTISTRUCT',
  ADD COLUMN license_token_count INT NOT NULL DEFAULT 1,
  ADD COLUMN resource_profile_version BIGINT NOT NULL DEFAULT 0;
UPDATE cae_job SET license_feature=CASE solver_type
  WHEN 'MOCK_NVH' THEN 'NVH' WHEN 'MOCK_CRASH' THEN 'CRASH'
  WHEN 'MOCK_CFD' THEN 'CFD' ELSE 'OPTISTRUCT' END;
ALTER TABLE cae_job_execution ADD CONSTRAINT fk_execution_reservation
  FOREIGN KEY(reservation_id) REFERENCES cae_resource_reservation(id);
INSERT INTO cae_compute_node(node_id,hostname,agent_url,cpu_total,memory_total_gb,solver_tags,running_jobs) VALUES
 ('node-a','node-a','http://worker-node-a:8000',16,32,JSON_ARRAY('STRUCTURE','NVH'),JSON_ARRAY()),
 ('node-b','node-b','http://worker-node-b:8000',32,64,JSON_ARRAY('STRUCTURE','CRASH'),JSON_ARRAY()),
 ('node-c','node-c','http://worker-node-c:8000',24,48,JSON_ARRAY('CFD','STRUCTURE'),JSON_ARRAY());
INSERT INTO cae_license_pool(feature,total_tokens) VALUES ('OPTISTRUCT',4),('NVH',2),('CRASH',2),('CFD',2);
INSERT INTO cae_resource_profile(name,cpu,memory_gb,walltime_min,license_tokens,allowed_analysis_types) VALUES
 ('SMALL',4,8,30,1,JSON_ARRAY('STRUCTURE','NVH','CRASH','CFD')),
 ('STANDARD',8,16,30,1,JSON_ARRAY('STRUCTURE','NVH','CRASH','CFD')),
 ('LARGE',16,32,60,1,JSON_ARRAY('STRUCTURE','NVH','CRASH','CFD'));
