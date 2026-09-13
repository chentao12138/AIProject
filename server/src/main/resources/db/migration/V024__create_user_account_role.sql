-- V024__create_user_account_role.sql
-- BUSINESS-019: production RBAC / admin authorization
-- Normalized role table for user accounts.
-- Existing accounts are backfilled with USER baseline role.

CREATE TABLE IF NOT EXISTS user_account_role (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    user_account_id BIGINT        NOT NULL,
    role            VARCHAR(32)   NOT NULL,
    created_at      DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_user_account_role_user_role (user_account_id, role),
    KEY idx_user_account_role_user_account_id (user_account_id),
    CONSTRAINT fk_user_account_role_user_account
        FOREIGN KEY (user_account_id) REFERENCES user_account (id),
    CONSTRAINT chk_user_account_role_role
        CHECK (role IN ('USER', 'ADMIN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Backfill existing accounts with USER baseline role.
-- New accounts receive USER transactionally at creation time.
INSERT INTO user_account_role (user_account_id, role, created_at)
SELECT ua.id, 'USER', NOW(6)
  FROM user_account ua
 WHERE NOT EXISTS (
       SELECT 1
         FROM user_account_role existing
        WHERE existing.user_account_id = ua.id
 );
