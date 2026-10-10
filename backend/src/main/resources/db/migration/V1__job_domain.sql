CREATE TABLE cae_project (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  code VARCHAR(64) NOT NULL,
  name VARCHAR(160) NOT NULL,
  description VARCHAR(1000),
  owner_id VARCHAR(64) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_project_code(code)
);
CREATE TABLE cae_project_member (
  project_id BIGINT NOT NULL,
  username VARCHAR(64) NOT NULL,
  member_role VARCHAR(32) NOT NULL DEFAULT 'CAE_ENGINEER',
  PRIMARY KEY(project_id, username),
  FOREIGN KEY(project_id) REFERENCES cae_project(id)
);
CREATE TABLE cae_model (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  project_id BIGINT NOT NULL,
  code VARCHAR(64) NOT NULL,
  name VARCHAR(160) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_project_model(project_id, code),
  FOREIGN KEY(project_id) REFERENCES cae_project(id)
);
CREATE TABLE cae_model_revision (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  model_id BIGINT NOT NULL,
  revision VARCHAR(64) NOT NULL,
  description VARCHAR(1000),
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_model_revision(model_id, revision),
  FOREIGN KEY(model_id) REFERENCES cae_model(id)
);
CREATE TABLE cae_job (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  job_no VARCHAR(40) NOT NULL,
  project_id BIGINT NOT NULL,
  model_revision_id BIGINT NOT NULL,
  analysis_type VARCHAR(24) NOT NULL,
  condition_code VARCHAR(64) NOT NULL,
  solver_type VARCHAR(40) NOT NULL,
  solver_profile VARCHAR(64) NOT NULL,
  solver_profile_version VARCHAR(32) NOT NULL DEFAULT '1',
  priority INT NOT NULL DEFAULT 50,
  status VARCHAR(32) NOT NULL,
  stage VARCHAR(32) NOT NULL DEFAULT 'CREATED',
  submitter_id VARCHAR(64) NOT NULL,
  input_manifest_uri VARCHAR(500),
  input_checksum VARCHAR(64),
  resource_profile_id VARCHAR(32) NOT NULL DEFAULT 'STANDARD',
  current_execution_id BIGINT,
  batch_id BIGINT,
  failure_code VARCHAR(64),
  failure_message VARCHAR(1000),
  raw_solver_code VARCHAR(100),
  raw_message VARCHAR(1000),
  case_path VARCHAR(500),
  workspace VARCHAR(500),
  input_json JSON NOT NULL,
  resource_json JSON NOT NULL,
  mock_json JSON NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  submitted_at DATETIME(6),
  started_at DATETIME(6),
  finished_at DATETIME(6),
  UNIQUE KEY uk_job_no(job_no),
  KEY idx_project_created(project_id, created_at, id),
  KEY idx_job_status_created(status, created_at),
  KEY idx_analysis_condition_created(analysis_type, condition_code, created_at),
  KEY idx_submitter_created(submitter_id, created_at),
  KEY idx_current_execution(current_execution_id),
  KEY idx_batch_id(batch_id),
  FOREIGN KEY(project_id) REFERENCES cae_project(id),
  FOREIGN KEY(model_revision_id) REFERENCES cae_model_revision(id)
);
CREATE TABLE cae_job_event (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  job_id BIGINT NOT NULL,
  event_type VARCHAR(64) NOT NULL,
  from_status VARCHAR(32),
  to_status VARCHAR(32),
  detail_json JSON NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  KEY idx_job_event(job_id,id),
  FOREIGN KEY(job_id) REFERENCES cae_job(id)
);
CREATE TABLE cae_audit_log (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  actor VARCHAR(64) NOT NULL,
  operation VARCHAR(64) NOT NULL,
  target_type VARCHAR(64) NOT NULL,
  target_id VARCHAR(64) NOT NULL,
  before_json JSON,
  after_json JSON,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  KEY idx_audit_target(target_type,target_id,created_at)
);

INSERT INTO cae_project(id,code,name,description,owner_id) VALUES
 (1,'BIW-DEMO','Synthetic BIW Platform','公开结构/模态示例，无企业数据','lead'),
 (2,'MULTI-PILOT','Multidiscipline Pilot','NVH/Crash/CFD synthetic pilots','lead');
INSERT INTO cae_project_member(project_id,username,member_role) VALUES
 (1,'engineer','CAE_ENGINEER'),(1,'lead','PROJECT_LEAD'),
 (2,'engineer','CAE_ENGINEER'),(2,'lead','PROJECT_LEAD');
INSERT INTO cae_model(id,project_id,code,name) VALUES
 (1,1,'BIW','Body In White Synthetic'),(2,2,'PILOT','Pilot Synthetic Vehicle');
INSERT INTO cae_model_revision(id,model_id,revision,description) VALUES
 (1,1,'V36','Baseline'),(2,1,'V37','Current'),(3,2,'P1','Pilot revision');
