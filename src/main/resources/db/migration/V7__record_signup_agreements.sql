ALTER TABLE ziper.users
    ADD COLUMN terms_agreed_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN privacy_agreed_at TIMESTAMP WITH TIME ZONE;

UPDATE ziper.users
SET terms_agreed_at = created_at, privacy_agreed_at = created_at
WHERE oauth_connect IS NULL AND password_hash IS NOT NULL;

CREATE TABLE ziper.oauth_signup_tickets (
    token_hash VARCHAR(64) PRIMARY KEY,
    provider VARCHAR(255) NOT NULL,
    provider_id VARCHAR(255) NOT NULL,
    email VARCHAR(255),
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_oauth_signup_tickets_expires_at ON ziper.oauth_signup_tickets (expires_at);
