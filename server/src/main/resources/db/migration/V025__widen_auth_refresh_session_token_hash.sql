-- V025__widen_auth_refresh_session_token_hash.sql
-- BUSINESS-018 fix: RefreshTokenService.hash() stores "hex:" + SHA-256 hex
-- (68 chars). V023 defined token_hash as VARCHAR(64), which rejects every
-- rotated refresh session with "Data too long for column 'token_hash'".

ALTER TABLE auth_refresh_session
    MODIFY COLUMN token_hash VARCHAR(80) NOT NULL;
