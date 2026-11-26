CREATE TABLE IF NOT EXISTS c_credentials (
 account_id CHAR(36) PRIMARY KEY REFERENCES accounts(id),
 password_hash VARCHAR(150) NOT NULL
);
CREATE TABLE IF NOT EXISTS c_verifications (
 id CHAR(36) PRIMARY KEY,
 account_id CHAR(36) NOT NULL,
 version INT NOT NULL,
 real_name VARCHAR(50) NOT NULL,
 student_no VARCHAR(50) NOT NULL,
 asset_id CHAR(36) NOT NULL,
 status VARCHAR(16) NOT NULL,
 reason VARCHAR(1500),
 created_at DATETIME(3) NOT NULL,
 UNIQUE(account_id,version),
 FOREIGN KEY(account_id) REFERENCES accounts(id),
 FOREIGN KEY(asset_id) REFERENCES assets(id)
);
CREATE TABLE IF NOT EXISTS c_messages (
 id CHAR(36) PRIMARY KEY,
 context_type VARCHAR(8) NOT NULL,
 context_id CHAR(36) NOT NULL,
 buyer_id CHAR(36) NOT NULL,
 seller_id CHAR(36) NOT NULL,
 sender_id CHAR(36) NOT NULL,
 content VARCHAR(1500) NOT NULL,
 created_at DATETIME(3) NOT NULL,
 INDEX c_thread(context_type,context_id,buyer_id,created_at),
 INDEX c_seller(seller_id,created_at),
 INDEX c_buyer(buyer_id,created_at)
);
