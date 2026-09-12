-- V022__create_user_account.sql
-- BUSINESS-017: production authentication foundation
-- 独立 user_account，subject 为稳定身份标识
-- learning_space.owner_subject 继续保存 subject 字符串，不做 FK

CREATE TABLE IF NOT EXISTS user_account (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    subject VARCHAR(64) NOT NULL,
    username VARCHAR(128) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uq_user_account_subject (subject),
    UNIQUE KEY uq_user_account_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
