-- V023__create_refresh_session.sql
-- BUSINESS-018: refresh token lifecycle
--  - opaque refresh token is never stored plaintext
--  - token_hash is SHA-256 hex and UNIQUE
--  - family_id groups rotated descendants
--  - FK to user_account with no CASCADE at DB level

CREATE TABLE IF NOT EXISTS auth_refresh_session (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    user_account_id   BIGINT        NOT NULL,
    family_id         BIGINT        NOT NULL,
    token_hash        VARCHAR(64)   NOT NULL,
    issued_at         DATETIME(6)   NOT NULL,
    expires_at        DATETIME(6)   NOT NULL,
    rotated_at        DATETIME(6)   NULL,
    revoked_at        DATETIME(6)   NULL,
    replaced_by_id    BIGINT        NULL,
    revoke_reason     VARCHAR(64)   NULL,
    created_at        DATETIME(6)   NOT NULL,
    updated_at        DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_auth_refresh_session_token_hash (token_hash),
    KEY idx_auth_refresh_session_family_id (family_id),
    KEY idx_auth_refresh_session_user_account_id (user_account_id),
    KEY idx_auth_refresh_session_expires_at (expires_at),
    CONSTRAINT fk_auth_refresh_session_user_account
        FOREIGN KEY (user_account_id) REFERENCES user_account (id),
    CONSTRAINT fk_auth_refresh_session_replaced_by
        FOREIGN KEY (replaced_by_id) REFERENCES auth_refresh_session (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
