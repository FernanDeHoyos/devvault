CREATE TABLE revoked_tokens (
    id VARCHAR(36) PRIMARY KEY,
    expires_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_revoked_tokens_expires_at ON revoked_tokens(expires_at);
