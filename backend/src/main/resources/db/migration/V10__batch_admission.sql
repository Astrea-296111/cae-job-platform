CREATE TABLE cae_batch (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 batch_no VARCHAR(48) NOT NULL UNIQUE,
 name VARCHAR(128) NOT NULL,
 project_id BIGINT NOT NULL,
 submitter_id VARCHAR(64) NOT NULL,
 idempotency_key VARCHAR(100) NOT NULL,
 request_fingerprint VARCHAR(64) NOT NULL,
 total INT NOT NULL,
 version BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uk_batch_intent(submitter_id,idempotency_key),
 KEY idx_batch_project(project_id,created_at,id),
 FOREIGN KEY(project_id) REFERENCES cae_project(id)
);
CREATE TABLE cae_batch_item (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 batch_id BIGINT NOT NULL,
 item_index INT NOT NULL,
 request_json JSON NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
 job_id BIGINT,
 failure_code VARCHAR(64),
 failure_message VARCHAR(500),
 UNIQUE KEY uk_batch_index(batch_id,item_index),
 UNIQUE KEY uk_batch_job(job_id),
 KEY idx_batch_pending(status,batch_id,item_index),
 FOREIGN KEY(batch_id) REFERENCES cae_batch(id),
 FOREIGN KEY(job_id) REFERENCES cae_job(id)
);
ALTER TABLE cae_job ADD CONSTRAINT fk_job_batch FOREIGN KEY(batch_id) REFERENCES cae_batch(id);

CREATE TABLE cae_batch_admission_gate(id INT PRIMARY KEY);
INSERT INTO cae_batch_admission_gate(id) VALUES(1);
