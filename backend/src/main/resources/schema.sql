CREATE TABLE IF NOT EXISTS material (
  code VARCHAR(20) PRIMARY KEY, name VARCHAR(100) NOT NULL, specification VARCHAR(120) NOT NULL,
  unit VARCHAR(20) NOT NULL, safe_stock INT NOT NULL CHECK (safe_stock >= 0),
  category VARCHAR(50) NOT NULL, stock INT NOT NULL DEFAULT 0 CHECK (stock >= 0)
);
CREATE TABLE IF NOT EXISTS receipt (
  number VARCHAR(60) PRIMARY KEY, received_on VARCHAR(20), supplier VARCHAR(150),
  department VARCHAR(80), operator VARCHAR(50) NOT NULL, created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6)
);
CREATE TABLE IF NOT EXISTS issue_request (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, applicant VARCHAR(50) NOT NULL,
  status VARCHAR(20) NOT NULL, reject_reason VARCHAR(300), reviewer VARCHAR(50),
  created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6), reviewed_at TIMESTAMP(6)
);
CREATE TABLE IF NOT EXISTS issue_item (
  request_id BIGINT NOT NULL REFERENCES issue_request(id), material_code VARCHAR(20) NOT NULL REFERENCES material(code),
  quantity INT NOT NULL CHECK (quantity > 0), PRIMARY KEY(request_id, material_code)
);
CREATE TABLE IF NOT EXISTS movement (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, occurred_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6),
  kind VARCHAR(20) NOT NULL, document_number VARCHAR(60) NOT NULL,
  material_code VARCHAR(20) NOT NULL REFERENCES material(code), quantity INT NOT NULL,
  balance INT NOT NULL, operator VARCHAR(50) NOT NULL
);
